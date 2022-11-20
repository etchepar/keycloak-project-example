FROM quay.io/keycloak/keycloak:19.0.3 as builder

USER root
#
# Copy custom KC image
#
# tidy the final image (remove original library - bis)
RUN rm -R /opt/keycloak

# Copy the custom distribution
COPY ./target/keycloak-* /opt/keycloak/
RUN chmod -R g+rwX /opt/keycloak

USER 1000


RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:19.0.3

# tidy the final image (remove original library - bis)
USER root
RUN rm -R /opt/keycloak

COPY --from=builder /opt/keycloak/ /opt/keycloak/
RUN chmod -R g+rwX /opt/keycloak
# go back to the KC default user
USER 1000

WORKDIR /opt/keycloak

RUN mkdir /opt/keycloak/export &&\
    mkdir /opt/keycloak/import &&\
    mkdir /opt/keycloak/conf/vault


ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]


        