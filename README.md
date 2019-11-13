# Datashare

[![Circle CI](https://circleci.com/gh/ICIJ/datashare.png?style=shield&circle-token=b7637e0aec84ab65d39ccd0d331bae27ba697299)](https://circleci.com/gh/ICIJ/datashare)
[![Crowdin](https://badges.crowdin.net/datashare/localized.svg)](https://crowdin.com/project/datashare)

## Download

https://datashare.icij.org/


## Documentation

Datashare's user guide can be found here: https://icij.gitbook.io/datashare/


## Description

Datashare is a free open-source desktop application developed by non-profit International Consortium of Investigative Journalists (ICIJ). 

Datashare allows investigative journalists to:
- access all their documents in one place locally on their computer while securing them from potential third-party interferences
- search pdfs, images, texts, spreadsheets, slides and any files, simultaneously
- automatically detect and filter by people, organizations and locations

## Translation of the interface

You're welcome to suggest translations on Datashare's Crowdin https://crwd.in/datashare. Please contact us if you would like to add a language.

## Installing and using

### Using with elasticsearch

You can download the script at datashare.icij.org.

To access web GUI, go in your documents folder and launch `path/to/datashare.sh` then connect datashare on http://localhost:8080

### Using only Named Entity Recognition

You can use the datashare docker container only for HTTP exposed name finding API.

Just run : 

    docker run -ti -p 8080:8080 -v /path/to/dist/:/home/datashare/dist icij/datashare:0.10 -m NER

A bit of explanation : 
- `-w` tells datashare to run the webserver. It is launched on 8080 that's why the port is mapped for docker
- `-m NER` runs datashare without index at all on a stateless mode
- `-v /path/to/dist:/home/datashare/dist` maps the directory where the NLP models will be read (and downloaded if they don't exist)

Then query with curl the server with : 

    curl -i localhost:8080/ner/findNames/CORENLP --data-binary @path/to/a/file.txt

The last path part (CORENLP) is the framework. You can choose it among CORENLP, IXAPIPE, MITIE or OPENNLP.    

### **Extract Text from Files** 
  
*Implementations*
  
  - [TikaDocument](https://github.com/ICIJ/extract/blob/extractlib/extract-lib/src/main/java/org/icij/extract/document/TikaDocument.java) from ICIJ/extract 
  
    [Apache Tika](https://tika.apache.org/) v1.18 (Apache Licence v2.0)
  
    with [Tesseract](https://github.com/tesseract-ocr/tesseract/wiki/4.0-with-LSTM) v4.0 alpha 


*Support*

  [Tika File Formats](https://tika.apache.org/1.18/formats.html)

  
### **Extract Persons, Organizations or Locations from Text** 
   
*Implementations*
  
  - `org.icij.datashare.text.nlp.corenlp.CorenlpPipeline` 
  
    [Stanford CoreNLP](http://stanfordnlp.github.io/CoreNLP) v3.8.0, 
    (Conditional Random Fields), 
    *Composite GPL v3+* 

  - `org.icij.datashare.text.nlp.ixapipe.IxapipePipeline` 
  
    [Ixa Pipes Nerc](https://github.com/ixa-ehu/ixa-pipe-nerc) v1.6.1, 
    (Perceptron), 
    *Apache Licence v2.0*

  - `org.icij.datashare.text.nlp.mitie.MitiePipeline` 
  
    [MIT Information Extraction](https://github.com/mit-nlp/MITIE) v0.8, 
    (Structural Support Vector Machines), 
    *Boost Software License v1.0*

  - `org.icij.datashare.text.nlp.opennlp.OpennlpPipeline` 
  
    [Apache OpenNLP](https://opennlp.apache.org/) v1.6.0, 
    (Maximum Entropy), 
    *Apache Licence v2.0*

  
*Natural Language Processing Stages Support*

| `NlpStage`       |
|------------------|
| `TOKEN`          |
| `SENTENCE`       |
| `POS`            |
| `NER`            |

*Named Entity Recognition Language Support*

| *`NlpStage.NER`*           | `ENGLISH`  | `SPANISH`  | `GERMAN`  | `FRENCH`  | `CHINESE` |
|---------------------------:|:----------:|:----------:|:---------:|:---------:|:---------:|
| `NlpPipeline.Type.CORENLP` |     X      |      X     |      X    |  (w/ EN)  |     X     |
| `NlpPipeline.Type.OPENNLP` |     X      |      X     |      -    |     X     |     -     |
| `NlpPipeline.Type.IXAPIPE` |     X      |      X     |      X    |     -     |     -     |
| `NlpPipeline.Type.MITIE`   |     X      |      X     |      X    |     -     |     -     |

*Named Entity Categories Support*

| `NamedEntity.Category` |
|----------------------  |
| `ORGANIZATION`         |
| `PERSON`               |
| `LOCATION`             |

*Parts-of-Speech Language Support*

|  *`NlpStage.POS`*          | `ENGLISH`  | `SPANISH`  | `GERMAN`  | `FRENCH`  |
|---------------------------:|:----------:|:----------:|:---------:|:---------:|
| `NlpPipeline.Type.CORE`    |     X      |      X     |     X     |     X     |
| `NlpPipeline.Type.OPEN`    |     X      |      X     |     X     |     X     |
| `NlpPipeline.Type.IXA`     |     X      |      X     |     X     |     X     |
| `NlpPipeline.Type.MITIE`   |     -      |      -     |      -    |     -     |


### **Store and Search Documents and Named Entities**

 *Implementations*
  
 - `org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer`
 
   [Elasticsearch](https://www.elastic.co/products/elasticsearch) v6.1.0, *Apache Licence v2.0*



## Compilation / Build

Requires 
[JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html),
[Maven 3](http://maven.apache.org/download.cgi) and a running [PostgreSQL](https://www.postgresql.org/) database with 
two databases `datashare` and `test` with write access for user `test` / password `test`.

```
mvn validate
mvn -pl datashare-api -am install
mvn -pl datashare-db liquibase:update
mvn test
```

## License

Datashare is released under the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.en.html)


## Feedback

We welcome feedback as well as contributions!

For any bug, question, comment or (pull) request, 

please contact us at datashare@icij.org
 
 
