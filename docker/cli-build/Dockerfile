# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-21

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends libgtk-3-0 xvfb \
    && rm -rf /var/lib/apt/lists/*
