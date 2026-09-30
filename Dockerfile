# 빌드 단계
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
# 테스트는 CI에서 돌리므로 제외
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

# 실행 단계
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/app.jar app.jar
USER app
EXPOSE 8080
# 힙·메모리 옵션은 배포 시 JAVA_OPTS로 넘긴다
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java -Duser.timezone=Asia/Seoul $JAVA_OPTS -jar app.jar"]
