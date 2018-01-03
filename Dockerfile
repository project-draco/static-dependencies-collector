FROM openjdk:8-jre-alpine

COPY build/distributions/sdc.zip /

RUN unzip /sdc.zip \
    && rm /sdc.zip \
    && apk add --no-cache bash
