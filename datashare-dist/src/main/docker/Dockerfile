FROM adoptopenjdk/openjdk11:jre-11.0.9.1_1-alpine

# install tesseract OCR and the 14 PDF standard fonts
# cf https://pdfbox.apache.org/1.8/cookbook/workingwithfonts.html
RUN apk add --update tesseract-ocr ttf-dejavu ttf-droid ttf-freefont ttf-liberation ttf-ubuntu-font-family

# add user/group datashare
RUN addgroup -g 1000 datashare && adduser -D -u 1000 -G datashare datashare

RUN mkdir -p /home/datashare/lib /home/datashare/data /home/datashare/dist /home/datashare/es/plugins /home/datashare/extensions /home/datashare/plugins
COPY lib /home/datashare/lib
RUN chown -R datashare:datashare /home/datashare/

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /home/datashare/
EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
