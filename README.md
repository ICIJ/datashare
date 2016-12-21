# DataShare

DataShare aims at allowing for valuable knowledge about people and companies 
locked within hundreds of pages of documents inside a computer to be sieved 
into indexes and shared securely within a network of trusted individuals, 
fostering unforeseen collaboration and prompting new and better investigations 
that uncover corruption, transnational crime and abuse of power.

[DataShare: connecting local data with a global collective intelligence](https://www.newschallenge.org/challenge/data/refinement/datashare-connecting-local-data-with-a-global-collective-intelligence)


## Features

An Open-ended Multilingual Information Extraction and Search Platform

*Data Sharing module to come...*

### **Extract Text from Files** 
  
*API*

 - `org.icij.datashare.text.extraction.FileParser` 
  
*Implementations*
  
  - `org.icij.datashare.text.extraction.tika.TikaFileParser` 
  
  [Apache Tika](https://tika.apache.org/) v1.14 (Apache licence)

*Support*

  [Tika File Formats](https://tika.apache.org/1.14/formats.html)

*Data Structures*

 - `org.icij.datashare.text.Language` 
 
 - `org.icij.datashare.text.Document`
  
### **Extract Persons, Organizations or Locations from Text** 
  
*API* 

 - `org.icij.datashare.text.nlp.NlpPipeline`  
  
*Implementations*
  
  - `org.icij.datashare.text.nlp.core.CoreNlpPipeline` 
  
  [Stanford CoreNLP](http://stanfordnlp.github.io/CoreNLP) v3.6.0, *(Conditional Random Fields)*, Composite GPL Version 3+ Licence

  - `org.icij.datashare.text.nlp.open.OpenNlpPipeline` 
  
  [Apache OpenNLP](https://opennlp.apache.org/) v1.6.0, *(Maximum Entropy)*, Apache Licence Version 2.0

  - `org.icij.datashare.text.nlp.gate.GateNlpPipeline` 
  
  [OEG UPM Entity Extractor](https://github.com/ICIJ/entity-extractor/tree/production), v1.1, *(JAPE Rules Grammar)*, based on [EPSRC Gate](https://gate.ac.uk/) v8.11, LGPL v3
    
  - `org.icij.datashare.text.nlp.mitie.MitieNlpPipeline` 
  
  [MIT Information Extraction](https://github.com/mit-nlp/MITIE) v0.8, *(Structural Support Vector Machines)*, Boost Software License Version 1.0

  - `org.icij.datashare.text.nlp.ixa.IxaNlpPipeline` 
  
  [Ixa Pipes Nerc](https://github.com/ixa-ehu/ixa-pipe-nerc) v1.6.1, *(Perceptron)*, Apache Licence Version 2.0

*Natural Language Processing Stages Support*

| `NlpStage`       |
|------------------|
| `TOKEN`          |
| `SENTENCE`       |
| `POS`            |
| `NER`            |

*Named Entity Recognition Language Support*

| *`NlpStage.NER`*           | `Language.ENGLISH`  | `Language.SPANISH`  | `Language.FRENCH`  | `Language.GERMAN`  |
|---------------------------:|:-------------------:|:-------------------:|:------------------:|:------------------:|
| `NlpPipeline.Type.CORE`    |          X          |          X          |          -         |          X         |
| `NlpPipeline.Type.OPEN`    |          X          |          X          |          X         |          -         |
| `NlpPipeline.Type.GATE`    |          X          |          X          |          X         |          X         |
| `NlpPipeline.Type.MITIE`   |          X          |          X          |          -         |          -         |
| `NlpPipeline.Type.IXA`     |          X          |          X          |          -         |          X         |

*Named Entity Categories Support*


| `NamedEntity.Category` |
|----------------------  |
| `ORGANIZATION`         |
| `PERSON`               |
| `LOCATION`             |

*Parts-of-Speech Language Support*

|  *`NlpStage.POS`*            | `Language.ENGLISH`  | `Language.SPANISH`  | `Language.FRENCH`  | `Language.GERMAN`  |
|-----------------------------:|:-------------------:|:-------------------:|:------------------:|:------------------:|
|   `NlpPipeline.Type.CORE`    |          X          |          X          |          X         |          X         |
|   `NlpPipeline.Type.OPEN`    |          X          |          X          |          X         |          X         |
|   `NlpPipeline.Type.IXA`     |          X          |          X          |          X         |          X         |


*Data Structures*
 
 - `org.icij.datashare.text.Language`

 - `org.icij.datashare.text.Document`

 - `org.icij.datashare.text.NamedEntity`

 - `org.icij.datashare.text.nlp.NlpStage`

 - `org.icij.datashare.text.nlp.NlpPipeline`
   
 - `org.icij.datashare.text.nlp.Tag`  
 
 - `org.icij.datashare.text.nlp.Annotation`


### **Store and Search Documents and Named Entities**

*API* 

 - `org.icij.datashare.text.indexing.Indexer`

 *Implementations*
  
 - `org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer`
 
  [Elasticsearch](https://www.elastic.co/products/elasticsearch), v5.1.1 (Apache licence v2)

*Data Structures*
 
 - `org.icij.datashare.text.NamedEntity`
   
 - `org.icij.datashare.text.Document`  
 

## Usage 

### Distribution Directory Structure

Build process yields the following structure

**`datashare-dist-<VERSION>-all`**

`|__ `**`dist`**

`|__ `**`lib`**

`|__ `**`logs`**

`|__ `**`scr`**

`|__ `**`src`**

`|__ start-cli`

`|__ start-idx`

`|__ start-ws`

`|__ stop-idx`

`|__ stop-ws`



### Execution

*Requirements*:

 - Version `JRE8+`,
 - File encoding `UTF-8`,
 - Memory `8+GB`

#### Command-Line Interface

**`./start-cli`**

**`--input-dir`**, `-i`:
Path towards source directory containing documents to be processed.
*Required*

`--output-dir`, `-o`:
Path towards directory where to write result files.
Defaults to system `/tmp` directory

`--pipeline`, `-p`:
NLP pipelines to be run; in {`GATE`,`CORE`,`OPEN`,`MITIE`, `IXA`}.
Defaults to `GATE`

`--parallelism`, `-t`:
Number of threads per NLP pipeline.
Defaults to `1`

`--stages`, `-s`:
NLP stages to be run by pipelines; in {`POS`,`NER`}.
Defaults to `NER`

`--entities`, `-e`:
Named entity categories to be extracted.
Defaults to  all: {`ORGANIZATION`,`PERSON`,`LOCATION`}

`--no-caching`:
Disable caching of pipeline's models and annotators.
Default is `--caching`

`--ocr`:
Enable OCR when parsing source documents.
Install Tesseract beforehand; very slow currently.
Defaults to `--no-ocr`

*examples:*

 - `start-cli --input-dir path/to/source/docs/`

 - `start-cli --input-dir path/to/source/docs/ -p OPEN,CORE -s POS,NER -e PERS,ORG --ocr` 


#### Web Server

**`./start-ws`**

*See all routes at `datashare/datashare-web/datashare-web-play/conf/routes`*

*Processing examples:*

  - `curl -XPOST 'localhost:9000/datashare/process/local/<INPUT_DIR>'`
  
  - `curl -XPOST 'localhost:9000/datashare/process/local/<INPUT_DIR>?parallelism=2'`
  
NB: concrete `INPUT_DIR` is evaluated on web server and must be escaped, eg `%2Fpath%2Fto%2Fsource%2Fdocs`

*Indexing examples:*
  
  - list all indices: `curl -XGET 'localhost:9000/datashare/index'`
    
  - commit index: `curl -XPUT 'localhost:9000/datashare/index/<INDEX>'`
    
  - delete index: `curl -XDELETE 'localhost:9000/datashare/index/<INDEX>'`
    
  - search all indices: `curl -XPOST 'localhost:9000/datashare/index/<QUERY_STRING>'`
  
  - search index/type/query: `curl -XPOST 'localhost:9000/datashare/index/<INDEX>/<TYPE>/<QUERY_STRING>'`
        
*See [Query String syntax](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-query-string-query.html#query-string-syntax)* 
       

#### Index

**`./start-idx`**


## Compilation / Build

Requires 
[JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) and 
[Maven 3](http://maven.apache.org/download.cgi)

From `datashare` root directory, type: `mvn package`

### Source Directory Stucture
 
**`datashare`**

`|__ `**`datashare-api`**

`|__ `**`datashare-cli`**

`|__ `**`datashare-dist`**

`|__ `**`datashare-extract`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-extract-tika`**

`|__ `**`datashare-index`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-index-elasticsearch`**

`|__ `**`datashare-nlp`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-nlp-corenlp`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-nlp-gate`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-nlp-ixapipe`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-nlp-mitie`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-nlp-opennlp`**

`|__ `**`datashare-web`**

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; **`|__ datashare-web-play`**


## Documentation

Browse the JavaDoc from **datashare/doc/index.html**


## License

DataShare is released under the [GNU General Public License](http://www.gnu.org/licenses/gpl.html)


## Feedback

We would be happy to get your feedback as well as your contributions!

For any bug, question, comment or (pull) request, 

please contact us at jmartin@icij.org or julien.pierre.martin@gmail.com


## What's next

 - Test suite
 
 - Handle Embedded documents with Tika
 
 - Embed Tesseract ([Tess4J](http://tess4j.sourceforge.net/))
 
 - Web module graphical user interface

 - Web module Security
 
 - User Management module
  
 - Networking module
 
 - Data Sharing module



