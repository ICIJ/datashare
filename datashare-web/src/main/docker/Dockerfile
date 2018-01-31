FROM jolokia/alpine-jre-8

# add user/group datashare
RUN addgroup -g 1000 datashare && adduser -D -u 1000 -G datashare datashare

COPY extract.jar /home/datashare/

CMD ["java", "-jar", "/home/datashare/extract.jar"]