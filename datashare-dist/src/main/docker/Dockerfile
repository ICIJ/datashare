FROM frolvlad/alpine-glibc

# install java Runtime for alpine cf https://wiki.alpinelinux.org/wiki/Installing_Oracle_Java
# without glibc stuffs because we're using alpine-glibc
RUN cd /tmp && \
  wget  --no-check-certificate --header "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" \
  "http://download.oracle.com/otn-pub/java/jdk/8u161-b12/2f38c3b165be4555a1fa6e98c45e0808/server-jre-8u161-linux-x64.tar.gz" && \
  mkdir -p /opt/java && cd /opt/java && \
  tar -zxf  /tmp/server-jre-8u161-linux-x64.tar.gz && \
  ln -s /opt/java/jdk1.8.0_161 /opt/java/current && \
  echo 'export JAVA_HOME=/opt/java/current' > /etc/profile.d/java.sh && \
  echo 'export PATH=$PATH:$JAVA_HOME/bin' >> /etc/profile.d/java.sh && \
  chmod +x /etc/profile.d/java.sh

# install tesseract OCR and the 14 PDF standard fonts
# cf https://pdfbox.apache.org/1.8/cookbook/workingwithfonts.html
RUN apk add --update tesseract-ocr ttf-dejavu ttf-droid ttf-freefont ttf-liberation ttf-ubuntu-font-family

# add user/group datashare
RUN addgroup -g 1000 datashare && adduser -D -u 1000 -G datashare datashare

RUN mkdir -p /home/datashare/lib /home/datashare/data /home/datashare/dist /home/datashare/app && \
  ln -s /home/datashare/data /home/datashare/app/data
COPY lib /home/datashare/lib
COPY app /home/datashare/app
RUN chown -R datashare:datashare /home/datashare/

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /home/datashare/
EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]