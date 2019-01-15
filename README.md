# DataShare

[![Circle CI](https://circleci.com/gh/ICIJ/datashare.png?style=shield&circle-token=b7637e0aec84ab65d39ccd0d331bae27ba697299)](https://circleci.com/gh/ICIJ/datashare)

DataShare aims at allowing for valuable knowledge about people and companies 
locked within hundreds of pages of documents inside a computer to be sieved 
into indexes and shared securely within a network of trusted individuals, 
fostering unforeseen collaboration and prompting new and better investigations 
that uncover corruption, transnational crime and abuse of power.

[DataShare: connecting local data with a global collective intelligence](https://www.newschallenge.org/challenge/data/refinement/datashare-connecting-local-data-with-a-global-collective-intelligence)


## Current Features

An Extensible Multilingual Information Extraction and Search Platform

 - Extract Text from Files; 
 - Extract Organizations, Persons and Locations from Text; 
 - Index and Search all

Multithreaded and Distributed Processings

Local or Remote Indexing


## Installing and using

### Using with elasticsearch
You can download the script [datashare.sh](datashare-dist/src/main/datashare.sh) and execute it. It will :

- download [redis](https://redis.io), [elasticsearch](https://www.elastic.co/) and datashare [docker](https://www.docker.com/docker-community) containers
- initialize an elasticsearch index with datashare mapping
- provide CLI to run datashare extract, index, name finding tasks
- provide a WEB GUI to run datashare extract, index, name finding tasks, and search in the documents

To access web GUI, go in your documents folder and launch `path/to/datashare.sh -w` then connect datashare on http://localhost:8080

If you want to avoid synchronization of NLP models (offline use) then do `export DS_JAVA_OPTS="-DDS_SYNC_NLP_MODELS=false"` before launching the `datashare.sh` script.

### Using only Named Entity Recognition

You can use the datashare docker container only for HTTP exposed name finding API.

Just run : 

    docker run -ti -p 8080:8080 -v /path/to/dist/:/home/datashare/dist icij/datashare:0.10 -m NER -w

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

  [Tika File Formats](https://tika.apache.org/1.15/formats.html)

  
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

| *`NlpStage.NER`*           | `ENGLISH`  | `SPANISH`  | `GERMAN`  | `FRENCH`  |
|---------------------------:|:----------:|:----------:|:---------:|:---------:|
| `NlpPipeline.Type.CORE`    |     X      |      X     |      X    |     -     |
| `NlpPipeline.Type.OPEN`    |     X      |      X     |      -    |     X     |
| `NlpPipeline.Type.IXA`     |     X      |      X     |      X    |     -     |
| `NlpPipeline.Type.MITIE`   |     X      |      X     |      X    |     -     |

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
[Maven 3](http://maven.apache.org/download.cgi)

From `datashare` root directory, type: `mvn package`


## License

DataShare is released under the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.en.html)


## Feedback

We welcome feedback as well as contributions!

For any bug, question, comment or (pull) request, 

please contact us at engineering@icij.org


## What's next
 
 - Data Sharing module
 
   - Networking module
   
   - Content Management module
     
   - User Management module
        
   - Request and Exchange Protocol
 
 
