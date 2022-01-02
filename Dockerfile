FROM maven:3.3.9-jdk-8 AS mvn

ADD . $MAVEN_HOME

RUN cd $MAVEN_HOME \
 && mvn -B clean package -DskipTests=true \
 && cp -r target/$(ls target | grep "\-dist$" | head -1) /dist


FROM ghcr.io/oppgjorskontoret/oxalis/oxalis:5.0.6-with-statistics-enabled

COPY --from=mvn /dist /oxalis/ext
