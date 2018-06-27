#!/usr/bin/env bash

if [ ! -f /opt/ext/stanford-corenlp-3.8.0.jar ]; then
    curl -o /opt/ext/ https://repo1.maven.org/maven2/edu/stanford/nlp/stanford-corenlp/3.8.0/stanford-corenlp-3.8.0.jar
fi

if [ ! -f /opt/ext/stanford-corenlp-3.8.0-models-german.jar ]; then
    curl -o /opt/ext/ https://repo1.maven.org/maven2/edu/stanford/nlp/stanford-corenlp/3.8.0/stanford-corenlp-3.8.0-models-german.jar
fi

if [ $# -eq 0 -o "${1:0:1}" = '-' ]; then
    JVM_ARGS="-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=1 -Djava.security.egd=file:/dev/./urandom -Dloader.path=BOOT-INF/lib,/opt/ext"

    /usr/bin/java ${JVM_ARGS} -jar /opt/db-migrator.jar "$@" \
    && exec /usr/bin/java ${JVM_ARGS} -jar /opt/smarti.jar "$@"
else
    exec "$@"
fi
