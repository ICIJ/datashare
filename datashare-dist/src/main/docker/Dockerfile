FROM adoptopenjdk/openjdk11:jre-11.0.16.1_1

# install tesseract OCR and the 14 PDF standard fonts
# cf https://pdfbox.apache.org/1.8/cookbook/workingwithfonts.html
RUN apt update && apt install -y \
  tesseract-ocr \
  fonts-dejavu-core \
  fonts-droid-fallback \
  fonts-freefont-ttf \
  fonts-liberation \
  fonts-ubuntu

# add user/group datashare
RUN addgroup --gid 1000 datashare && adduser --disabled-password -u 1000 --system datashare

RUN mkdir -p \
  /home/datashare/lib \
  /home/datashare/data \
  /home/datashare/dist \
  /home/datashare/es/plugins \
  /home/datashare/extensions \
  /home/datashare/plugins

COPY lib /home/datashare/lib
RUN chown -R datashare:datashare /home/datashare/

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /home/datashare/
EXPOSE 8080

USER datashare
ENTRYPOINT ["/entrypoint.sh"]