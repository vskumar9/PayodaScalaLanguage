# eventManagement-consumer/Dockerfile
# ---------- build stage: produce fat jar ----------
FROM hseeberger/scala-sbt:11.0.13_1.6.1_2.13.17 AS build
WORKDIR /app

# copy sources
COPY . /app

# run assembly (produces target/scala-2.13/<name>-assembly.jar)
RUN sbt -no-colors clean assembly

# ---------- runtime stage ----------
FROM openjdk:11-jre-slim

# install netcat for wait-for.sh
RUN apt-get update && apt-get install -y --no-install-recommends netcat-openbsd ca-certificates && rm -rf /var/lib/apt/lists/*

# create non-root user
RUN addgroup --system app && adduser --system --ingroup app app

WORKDIR /app

# copy assembled jar (use a glob for whatever sbt-assembly named it)
# adjust the glob if your project creates a differently named jar
ARG JAR_NAME_GLOB="target/scala-2.13/*-assembly.jar"
COPY --from=build /app/${JAR_NAME_GLOB} /app/app-assembly.jar

# copy wait script (ensure you have wait-for.sh in project root)
COPY wait-for.sh /app/wait-for.sh
RUN chmod +x /app/wait-for.sh || true

EXPOSE 0

ENV KAFKA_BOOTSTRAP_SERVERS="kafka-1:9092"
ENV WAIT_TIMEOUT=60
ENV JAVA_OPTS="-Xms128m -Xmx256m"

USER app

# Wait for Kafka then run the fat jar
CMD ["sh", "-c", "/app/wait-for.sh ${KAFKA_BOOTSTRAP_SERVERS} ${WAIT_TIMEOUT} && exec java $JAVA_OPTS -jar /app/app-assembly.jar"]
