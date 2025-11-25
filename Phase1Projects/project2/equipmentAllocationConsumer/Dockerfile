# equipment-allocation-consumer/Dockerfile
# ---------- build stage: produce fat jar ----------
FROM hseeberger/scala-sbt:11.0.13_1.6.1_2.13.17 AS build
WORKDIR /app

COPY . /app

# create assembly jar
RUN sbt -no-colors clean assembly

# ---------- runtime stage ----------
FROM openjdk:11-jre-slim

RUN apt-get update && apt-get install -y --no-install-recommends netcat-openbsd ca-certificates && rm -rf /var/lib/apt/lists/*

RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app

ARG JAR_NAME_GLOB="target/scala-2.13/*-assembly.jar"
COPY --from=build /app/${JAR_NAME_GLOB} /app/app-assembly.jar

COPY wait-for.sh /app/wait-for.sh
RUN chmod +x /app/wait-for.sh || true

EXPOSE 0

ENV KAFKA_BOOTSTRAP_SERVERS="kafka-1:9092"
ENV WAIT_TIMEOUT=60
ENV JAVA_OPTS="-Xms128m -Xmx256m"

USER app

CMD ["sh", "-c", "/app/wait-for.sh ${KAFKA_BOOTSTRAP_SERVERS} ${WAIT_TIMEOUT} && exec java $JAVA_OPTS -jar /app/app-assembly.jar"]
