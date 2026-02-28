# Etapa 1: Construcción
FROM eclipse-temurin:22-jdk AS buildstage

RUN apt-get update && apt-get install -y maven

WORKDIR /app

COPY pom.xml .
COPY src /app/src

RUN mvn clean package -DskipTests

# Etapa 2: Ejecución
FROM eclipse-temurin:22-jdk

WORKDIR /app

# Copiar el JAR compilado
COPY --from=buildstage /app/target/*.jar /app/ubication-processor.jar

# Exponer puerto
EXPOSE 8092

ENTRYPOINT ["java", "-jar", "/app/ubication-processor.jar"]
