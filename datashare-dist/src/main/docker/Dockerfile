FROM eclipse-temurin:21-jammy

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

ENV DATASHARE_LEGACY_HOME=/home/datashare
ENV DATASHARE_HOME=/home/datashare/.local/share/datashare

RUN mkdir -p \
  $DATASHARE_HOME \
  $DATASHARE_HOME/index/tmp \
  $DATASHARE_LEGACY_HOME \
  $DATASHARE_LEGACY_HOME/lib \
  $DATASHARE_LEGACY_HOME/data \
  $DATASHARE_LEGACY_HOME/dist \
  $DATASHARE_LEGACY_HOME/es/plugins \
  $DATASHARE_LEGACY_HOME/extensions \
  $DATASHARE_LEGACY_HOME/plugins

RUN ln -s $DATASHARE_LEGACY_HOME/data $DATASHARE_LEGACY_HOME/Datashare
RUN ln -s $DATASHARE_LEGACY_HOME/lib $DATASHARE_HOME/lib
RUN ln -s $DATASHARE_LEGACY_HOME/dist $DATASHARE_HOME/dist
RUN ln -s $DATASHARE_LEGACY_HOME/es  $DATASHARE_HOME/es
RUN ln -s $DATASHARE_LEGACY_HOME/extensions $DATASHARE_HOME/extensions
RUN ln -s $DATASHARE_LEGACY_HOME/plugins $DATASHARE_HOME/plugins

COPY lib /home/datashare/lib
RUN chown -R datashare:datashare /home/datashare/

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /home/datashare/
EXPOSE 8080

USER datashare
ENTRYPOINT ["/entrypoint.sh"]