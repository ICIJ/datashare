FROM jolokia/alpine-jre-8

# add user/group datashare
RUN addgroup -g 1000 datashare && adduser -D -u 1000 -G datashare datashare

RUN mkdir -p /home/datashare/lib
COPY lib /home/datashare/lib
RUN chown -R datashare:datashare /home/datashare/
RUN mkdir /data && chown datashare:datashare /data
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME /data
EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]