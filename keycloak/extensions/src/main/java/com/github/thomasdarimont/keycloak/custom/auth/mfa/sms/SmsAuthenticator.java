package com.github.thomasdarimont.keycloak.custom.auth.mfa.sms;

import com.github.thomasdarimont.keycloak.custom.auth.mfa.sms.client.SmsClientFactory;
import com.github.thomasdarimont.keycloak.custom.auth.mfa.sms.credentials.SmsCredentialModel;
import com.github.thomasdarimont.keycloak.custom.auth.trusteddevice.action.ManageTrustedDeviceAction;
import com.github.thomasdarimont.keycloak.custom.support.ConfigUtils;
import com.google.auto.service.AutoService;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.credential.CredentialModel;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.provider.ServerInfoAwareProviderFactory;
import org.keycloak.representations.IDToken;
import org.keycloak.sessions.AuthenticationSessionModel;

import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@JBossLog
public class SmsAuthenticator implements Authenticator {

    static final String TEMPLATE_LOGIN_SMS = "login-sms.ftl";

    public static final int VERIFY_CODE_LENGTH = 6;

    public static final int CODE_TTL = 300;

    static final String CONFIG_CODE_LENGTH = "length";
    static final String CONFIG_MAX_ATTEMPTS = "attempts";
    static final String CONFIG_CODE_TTL = "ttl";
    static final String CONFIG_SENDER = "sender";
    static final String CONFIG_CLIENT = "client";
    static final String CONFIG_PHONENUMBER_PATTERN = "phoneNumberPattern";
    static final String CONFIG_USE_WEBOTP = "useWebOtp";

    public static final String AUTH_NOTE_CODE = "smsCode";
    static final String AUTH_NOTE_ATTEMPTS = "smsAttempts";

    static final String ERROR_SMS_AUTH_INVALID_NUMBER = "smsAuthInvalidNumber";
    static final String ERROR_SMS_AUTH_CODE_EXPIRED = "smsAuthCodeExpired";
    static final String ERROR_SMS_AUTH_CODE_INVALID = "smsAuthCodeInvalid";
    static final String ERROR_SMS_AUTH_SMS_NOT_SENT = "smsAuthSmsNotSent";
    static final String ERROR_SMS_AUTH_ATTEMPTS_EXCEEDED = "smsAuthAttemptsExceeded";

    @Override
    public void authenticate(AuthenticationFlowContext context) {

        if (context.getAuthenticationSession().getAuthNote(AUTH_NOTE_CODE) != null) {
            // avoid sending resending code on reload
            context.challenge(generateLoginForm(context, context.form()).createForm(TEMPLATE_LOGIN_SMS));
            return;
        }

        UserModel user = context.getUser();
        String phoneNumber = extractPhoneNumber(context.getSession(), context.getRealm(), user);
        AuthenticatorConfigModel authenticatorConfig = context.getAuthenticatorConfig();
        boolean validPhoneNumberFormat = validatePhoneNumberFormat(phoneNumber, authenticatorConfig);
        if (!validPhoneNumberFormat) {
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
                    generateErrorForm(context, ERROR_SMS_AUTH_INVALID_NUMBER)
                            .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }

        // TODO check for phoneNumberVerified

        sendCodeAndChallenge(context, user, phoneNumber, false);
    }

    protected String extractPhoneNumber(KeycloakSession session, RealmModel realm, UserModel user) {

        Optional<CredentialModel> maybeSmsCredential = user.credentialManager().getStoredCredentialsByTypeStream(SmsCredentialModel.TYPE).findFirst();
        if (maybeSmsCredential.isEmpty()) {
            return null;
        }

        CredentialModel credentialModel = maybeSmsCredential.get();

        SmsCredentialModel smsModel = new SmsCredentialModel(credentialModel);
        smsModel.readCredentialData();

        String phoneNumber = smsModel.getPhoneNumber();
        if (phoneNumber == null && Boolean.parseBoolean(user.getFirstAttribute(IDToken.PHONE_NUMBER_VERIFIED))) {
            // we use the verified phone-number from the user attributes as a fallback
            phoneNumber = user.getFirstAttribute(IDToken.PHONE_NUMBER);
        }

        return phoneNumber;
    }

    protected void sendCodeAndChallenge(AuthenticationFlowContext context, UserModel user, String phoneNumber, boolean resend) {
        log.infof("Sending code via SMS. resend=%s", resend);

        boolean codeSent = sendSmsWithCode(context, user, phoneNumber);

        if (!codeSent) {
            Response errorPage = generateErrorForm(context, null)
                    .setError(ERROR_SMS_AUTH_SMS_NOT_SENT, "Sms Client")
                    .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR);
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, errorPage);
            return;
        }

        context.challenge(generateLoginForm(context, context.form())
                .setAttribute("resend", resend)
                .setInfo("smsSentInfo", PhoneNumberUtils.abbreviatePhoneNumber(phoneNumber))
                .createForm(TEMPLATE_LOGIN_SMS));
    }

    protected LoginFormsProvider generateLoginForm(AuthenticationFlowContext context, LoginFormsProvider form) {
        return form.setAttribute("realm", context.getRealm());
    }

    protected boolean sendSmsWithCode(AuthenticationFlowContext context, UserModel user, String phoneNumber) {

        AuthenticatorConfigModel configModel = context.getAuthenticatorConfig();
        int length = Integer.parseInt(ConfigUtils.getConfigValue(configModel, CONFIG_CODE_LENGTH, "6"));
        int ttl = Integer.parseInt(ConfigUtils.getConfigValue(configModel, CONFIG_CODE_TTL, "300"));
        Map<String, String> clientConfig = ConfigUtils.getConfig(configModel, Collections.singletonMap("client", SmsClientFactory.MOCK_SMS_CLIENT));
        boolean useWebOtp = Boolean.parseBoolean(ConfigUtils.getConfigValue(configModel, CONFIG_USE_WEBOTP, "true"));

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();

        return createSmsCodeSender(context).sendVerificationCode(session, realm, user, phoneNumber, clientConfig, length, ttl, useWebOtp, authSession);
    }

    protected SmsCodeSender createSmsCodeSender(AuthenticationFlowContext context) {
        return new SmsCodeSender();
    }

    protected boolean validatePhoneNumberFormat(String phoneNumber, AuthenticatorConfigModel configModel) {

        if (phoneNumber == null) {
            return false;
        }

        String pattern = ConfigUtils.getConfigValue(configModel, CONFIG_PHONENUMBER_PATTERN, ".*");
        return phoneNumber.matches(pattern);
    }


    @Override
    public void action(AuthenticationFlowContext context) {

        var formParams = context.getHttpRequest().getDecodedFormParameters();

        if (formParams.containsKey("resend")) {
            UserModel user = context.getUser();
            String phoneNumber = extractPhoneNumber(context.getSession(), context.getRealm(), user);
            sendCodeAndChallenge(context, user, phoneNumber, true);
            return;
        }

        String codeInput = formParams.getFirst("code");

        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        AuthenticatorConfigModel configModel = context.getAuthenticatorConfig();
        int attempts = Integer.parseInt(Optional.ofNullable(authSession.getAuthNote(AUTH_NOTE_ATTEMPTS)).orElse("0"));
        int maxAttempts = Integer.parseInt(ConfigUtils.getConfigValue(configModel, CONFIG_MAX_ATTEMPTS, "5"));
        if (attempts >= maxAttempts) {
            log.info("To many invalid attempts.");
            Response errorPage = generateErrorForm(context, ERROR_SMS_AUTH_ATTEMPTS_EXCEEDED)
                    .createErrorPage(Response.Status.BAD_REQUEST);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, errorPage);
            return;
        }

        String codeExpected = authSession.getAuthNote(AUTH_NOTE_CODE);
        String codeExpireAt = authSession.getAuthNote("codeExpireAt");

        if (codeExpected == null || codeExpireAt == null) {
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
                    context.form().createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }

        boolean valid = codeInput.equals(codeExpected);
        if (!valid) {
            Response errorPage = generateErrorForm(context, null)
                    .setErrors(List.of(new FormMessage("code", ERROR_SMS_AUTH_CODE_INVALID)))
                    .setAttribute("showResend", "")
                    .createForm(TEMPLATE_LOGIN_SMS);
            handleFailure(context, AuthenticationFlowError.INVALID_CREDENTIALS, errorPage);
            return;
        }

        if (isCodeExpired(codeExpireAt)) {
            Response errorPage = generateErrorForm(context, null)
                    .setErrors(List.of(new FormMessage("code", ERROR_SMS_AUTH_CODE_EXPIRED)))
                    .setAttribute("showResend", "")
                    .createErrorPage(Response.Status.BAD_REQUEST);
            handleFailure(context, AuthenticationFlowError.EXPIRED_CODE, errorPage);
            return;
        }

        if (formParams.containsKey("register-trusted-device")) {
            context.getUser().addRequiredAction(ManageTrustedDeviceAction.ID);
        }

        context.success();
    }

    protected void handleFailure(AuthenticationFlowContext context, AuthenticationFlowError error, Response errorPage) {

        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        int attempts = Integer.parseInt(Optional.ofNullable(authSession.getAuthNote(AUTH_NOTE_ATTEMPTS)).orElse("0"));
        attempts++;
        authSession.setAuthNote(AUTH_NOTE_ATTEMPTS, "" + attempts);

        context.failureChallenge(error, errorPage);
    }

    protected boolean isCodeExpired(String codeExpireAt) {
        return Long.parseLong(codeExpireAt) < System.currentTimeMillis();
    }

    protected LoginFormsProvider generateErrorForm(AuthenticationFlowContext context, String error) {

        LoginFormsProvider form = context.form();
        generateLoginForm(context, form);

        if (error != null) {
            form.setError(error);
        }

        return form;
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {

        boolean configuredFor = user.credentialManager().isConfiguredFor(SmsCredentialModel.TYPE);

        // we only support 2FA with SMS for users with Phone Numbers
        return configuredFor && extractPhoneNumber(session, realm, user) != null;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // NOOP
    }

    @Override
    public void close() {
        // NOOP
    }

    @AutoService(AuthenticatorFactory.class)
    public static class Factory implements AuthenticatorFactory, ServerInfoAwareProviderFactory {

        public static final SmsAuthenticator INSTANCE = new SmsAuthenticator();

        private static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

        static {
            List<ProviderConfigProperty> list = ProviderConfigurationBuilder
                    .create()

                    .property().name(SmsAuthenticator.CONFIG_CODE_LENGTH)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Code length")
                    .defaultValue(VERIFY_CODE_LENGTH)
                    .helpText("The length of the generated Code.")
                    .add()

                    .property().name(SmsAuthenticator.CONFIG_CODE_TTL)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Time-to-live")
                    .defaultValue(CODE_TTL)
                    .helpText("The time to live in seconds for the code to be valid.")
                    .add()

                    .property().name(SmsAuthenticator.CONFIG_MAX_ATTEMPTS)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Max Attempts")
                    .defaultValue("5")
                    .helpText("Max attempts for Code.")
                    .add()

                    .property().name(SmsAuthenticator.CONFIG_SENDER)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Sender")
                    .defaultValue("$realmDisplayName")
                    .helpText("Denotes the message sender of the SMS. Defaults to $realmDisplayName")
                    .add()

                    .property().name(SmsAuthenticator.CONFIG_CLIENT)
                    .type(ProviderConfigProperty.LIST_TYPE)
                    .options(SmsClientFactory.MOCK_SMS_CLIENT)
                    .label("Client")
                    .defaultValue(SmsClientFactory.MOCK_SMS_CLIENT)
                    .helpText("Denotes the client to send the SMS")
                    .add()

                    .property().name(SmsAuthenticator.CONFIG_PHONENUMBER_PATTERN)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Phone Number Pattern")
                    .defaultValue("\\+49.*")
                    .helpText("Regex Pattern for validation of Phone Numbers")
                    .add()

                    .property().name(SmsAuthenticator.CONFIG_USE_WEBOTP)
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .label("Use Web OTP")
                    .defaultValue(true)
                    .helpText("Appends the Web OTP fragment '@domain #code' after a newline to the sms message.")
                    .add()

                    .build();

            CONFIG_PROPERTIES = Collections.unmodifiableList(list);
        }

        @Override
        public String getId() {
            return "acme-sms-authenticator";
        }

        @Override
        public String getDisplayType() {
            return "Acme: SMS Authentication";
        }

        @Override
        public String getHelpText() {
            return "Validates a code sent via SMS.";
        }

        @Override
        public String getReferenceCategory() {
            return SmsCredentialModel.TYPE;
        }

        @Override
        public boolean isConfigurable() {
            return true;
        }

        @Override
        public boolean isUserSetupAllowed() {
            return false;
        }

        @Override
        public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
            return REQUIREMENT_CHOICES;
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return CONFIG_PROPERTIES;
        }

        @Override
        public Authenticator create(KeycloakSession session) {
            return INSTANCE;
        }

        @Override
        public void init(Config.Scope config) {
            // NOOP
        }

        @Override
        public void postInit(KeycloakSessionFactory factory) {
            // NOOP
        }

        @Override
        public void close() {
            // NOOP
        }

        @Override
        public Map<String, String> getOperationalInfo() {
            return Collections.singletonMap("availableClients", SmsClientFactory.getAvailableClientNames().toString());
        }

    }
}