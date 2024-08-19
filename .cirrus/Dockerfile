ARG CIRRUS_AWS_ACCOUNT
FROM ${CIRRUS_AWS_ACCOUNT}.dkr.ecr.eu-central-1.amazonaws.com/base:j17-latest

USER root
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y \
      dotnet-sdk-8.0 \
      mono-complete \
    && rm -rf /var/lib/apt/lists/*

USER sonarsource
WORKDIR /home/sonarsource
RUN dotnet tool install --global dotnet-sonarscanner --version 8.0
