# equipment-allocation/Dockerfile
FROM hseeberger/scala-sbt:11.0.13_1.6.1_2.13.17 AS build
WORKDIR /app
COPY . /app
RUN sbt clean compile stage

FROM openjdk:11-jre-slim
RUN apt-get update && apt-get install -y --no-install-recommends netcat-openbsd ca-certificates && rm -rf /var/lib/apt/lists/*

RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app

COPY --from=build /app/target/universal/stage /app
COPY wait-for.sh /app/wait-for.sh
RUN chmod +x /app/wait-for.sh && chmod +x /app/bin/* || true

EXPOSE 9000

ENV PLAY_HTTP_SECRET="b0f4f35a4f9c4e8a9e8d1c7f3a8b2c5d7f9e1a3c4b6d8f0e2a4c6e8f0a2b4c"
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV KAFKA_BOOTSTRAP_SERVERS="kafka-1:9092"
ENV WAIT_TIMEOUT=60
ENV PLAY_PORT=9000

USER app

# Wait for Kafka then start Play app. Replace launcher name if different.
CMD ["sh","-c","/app/wait-for.sh ${KAFKA_BOOTSTRAP_SERVERS} ${WAIT_TIMEOUT} && exec /app/bin/equipment-allocation -Dplay.http.secret.key=$PLAY_HTTP_SECRET $JAVA_OPTS -Dconfig.file=conf/application.conf -Dplay.server.http.port=${PLAY_PORT}"]
