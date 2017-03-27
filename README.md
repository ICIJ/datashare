# DataShare

DataShare aims at allowing for valuable knowledge about people and companies 
locked within hundreds of pages of documents inside a computer to be sieved 
into indexes and shared securely within a network of trusted individuals, 
fostering unforeseen collaboration and prompting new and better investigations 
that uncover corruption, transnational crime and abuse of power.

[DataShare: connecting local data with a global collective intelligence](https://www.newschallenge.org/challenge/data/refinement/datashare-connecting-local-data-with-a-global-collective-intelligence)


## Current Features

An Open-ended Multilingual Information Extraction and Search Platform

 - Extract Text from Files; 
 - Extract Organizations, Persons and Locations from Text; 
 - Index and Search all

Multithreaded processings 

Distributed processings

Remote or Embedded Index 

Web API 

### **Extract Text from Files** 
  
*API*

 - **`org.icij.datashare.text.extraction.FileParser`** 

 - `org.icij.datashare.text.SourcePath`

 - `org.icij.datashare.text.Document`
  
  
*Implementations*
  
  - `org.icij.datashare.text.extraction.tika.TikaFileParser` 
  
    [Apache Tika](https://tika.apache.org/) v1.14 (Apache Licence v2.0)
  
    with [Tess4J](http://tess4j.sourceforge.net/) v3.3.0 (Apache Licence v2.0),
    [Tesseract](https://github.com/tesseract-ocr/tesseract/wiki/4.0-with-LSTM) v4.0 alpha compiled for arch x86-64 


*Support*

  [Tika File Formats](https://tika.apache.org/1.14/formats.html)

  
### **Extract Persons, Organizations or Locations from Text** 
  
*API* 

 - **`org.icij.datashare.text.nlp.NlpPipeline`**  

 - `org.icij.datashare.text.Document`

 - `org.icij.datashare.text.Language`
 
 - `org.icij.datashare.text.nlp.Annotation`  

 - `org.icij.datashare.text.NamedEntity`

   
*Implementations*
  
  - `org.icij.datashare.text.nlp.core.CoreNlpPipeline` 
  
    [Stanford CoreNLP](http://stanfordnlp.github.io/CoreNLP) v3.7.0, (Conditional Random Fields), 
  *Composite GPL v3+* 

  - `org.icij.datashare.text.nlp.gate.GateNlpPipeline` 
    
    [OEG UPM Entity Extractor](https://github.com/ICIJ/entity-extractor/tree/production) v1.1, (JAPE Rules Grammar), 
    based on [EPSRC Gate](https://gate.ac.uk/) v8.11, *LGPL v3*
  
  - `org.icij.datashare.text.nlp.ixa.IxaNlpPipeline` 
  
    [Ixa Pipes Nerc](https://github.com/ixa-ehu/ixa-pipe-nerc) v1.6.1, (Perceptron), 
  *Apache Licence v2.0*

  - `org.icij.datashare.text.nlp.mitie.MitieNlpPipeline` 
  
    [MIT Information Extraction](https://github.com/mit-nlp/MITIE) v0.8, (Structural Support Vector Machines), 
  *Boost Software License v1.0*

  - `org.icij.datashare.text.nlp.open.OpenNlpPipeline` 
  
    [Apache OpenNLP](https://opennlp.apache.org/) v1.7.2, (Maximum Entropy), 
  *Apache Licence v2.0*

  
*Natural Language Processing Stages Support*

| `NlpStage`       |
|------------------|
| `TOKEN`          |
| `SENTENCE`       |
| `POS`            |
| `NER`            |

*Named Entity Recognition Language Support*

| *`NlpStage.NER`*           | `ENGLISH`  | `SPANISH`  | `FRENCH`  | `GERMAN`  |
|---------------------------:|:----------:|:----------:|:---------:|:---------:|
| `NlpPipeline.Type.GATE`    |     X      |      X     |      X    |     X     |
| `NlpPipeline.Type.CORE`    |     X      |      X     |      -    |     X     |
| `NlpPipeline.Type.OPEN`    |     X      |      X     |      X    |     -     |
| `NlpPipeline.Type.IXA`     |     X      |      X     |      -    |     X     |
| `NlpPipeline.Type.MITIE`   |     X      |      X     |      -    |     -     |

*Named Entity Categories Support*

| `NamedEntity.Category` |
|----------------------  |
| `ORGANIZATION`         |
| `PERSON`               |
| `LOCATION`             |

*Parts-of-Speech Language Support*

|  *`NlpStage.POS`*          | `ENGLISH`  | `SPANISH`  | `FRENCH`  | `GERMAN`  |
|---------------------------:|:----------:|:----------:|:---------:|:---------:|
| `NlpPipeline.Type.GATE`    |     -      |      -     |      -    |     -     |
| `NlpPipeline.Type.CORE`    |     X      |      X     |     X     |     X     |
| `NlpPipeline.Type.OPEN`    |     X      |      X     |     X     |     X     |
| `NlpPipeline.Type.IXA`     |     X      |      X     |     X     |     X     |
| `NlpPipeline.Type.MITIE`   |     -      |      -     |      -    |     -     |


### **Store and Search Documents and Named Entities**

*API* 

 - **`org.icij.datashare.text.indexing.Indexer`**
 
 - `org.icij.datashare.text.Document`
 
 - `org.icij.datashare.text.NamedEntity`


 *Implementations*
  
 - `org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer`
 
   [Elasticsearch](https://www.elastic.co/products/elasticsearch) v5.1.1, *Apache Licence v2.0*



## Compilation / Build

Requires 
[JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) and 
[Maven 3](http://maven.apache.org/download.cgi)

From `datashare` root directory, type: `mvn package`

## Usage 

### Distribution Directory Structure

Build process yields the following structure

**`datashare-dist-<VERSION>-all`**

`|__ `**`lib`**

`|__ `**`logs`**

`|__ `**`opt`**

`|__ `**`src`**

`|__ start-cli`

`|__ start-cli-with-idx`

`|__ start-idx`

`|__ stop-idx`

`|__ start-ws`

`|__ start-ws-with-idx`

`|__ stop-ws`


### Execution

*Requirements*:

 - Version `JRE8+`,
 - File encoding `UTF-8`,
 - Memory `8+GB`

#### Command-Line Interface

**`./start-cli`**

`--stages`, `s`:
Processing stages to be run. 
Defaults to all: {`SCANNING`, `PARSING`, `NLP`}

`--node`, `n`:
Run as a cluster node.

`--input-dir`, `-i`:
Path towards source directory containing documents to be processed.
*Required if not a node or if --scanning-node*

`--nlp-pipelines`, `-p`:
NLP pipelines to be run; in {`GATE`,`CORE`,`OPEN`,`MITIE`,`IXA`}.
Defaults to `GATE`

`--nlp-parallelism`, `-t`:
Number of threads per NLP pipeline.
Defaults to `1`

`--nlp-stages`, `-s`:
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
Defaults to `--no-ocr`

*Command examples:*

Stand-alone 

 - `./start-cli-with-idx --input-dir path/to/source/docs/`

 - `./start-cli-with-idx --input-dir path/to/source/docs/ -p OPEN,CORE -s POS,NER -e PERS,ORG --ocr` 

Node 

 - `./start-cli --node --stages SCANNING,PARSING --input-dir path/to/source/docs/ --ocr --index-hostnames http://192.168.0.1 --index-hostports 9300` 
 
 - `./start-cli --node --stages NLP -pipelines OPEN,CORE --nlp-stages NER -e PERS,ORG --ocr  --index-hostnames http://192.168.0.1 --index-hostports 9300` 


#### Web Server

**`./start-ws`**

*See all routes at `datashare/datashare-web/datashare-web-play/conf/routes`*

*Processing examples:*

  - `curl -XPOST 'localhost:9000/datashare/process/<INPUT_DIR>'`
  
  - `curl -XPOST 'localhost:9000/datashare/process/<INPUT_DIR>?parallelism=2'`
  
NB: concrete `INPUT_DIR` is evaluated on web server and must be escaped, eg `%2Fpath%2Fto%2Fsource%2Fdocs`

TODO: pass options as JSON

*Indexing examples:*
  
  - list all indices: `curl -XGET 'localhost:9000/datashare/index'`
    
  - commit index: `curl -XPUT 'localhost:9000/datashare/index/<INDEX>'`
    
  - delete index: `curl -XDELETE 'localhost:9000/datashare/index/<INDEX>'`
    
  - search all indices: `curl -XPOST 'localhost:9000/datashare/index?<QUERY_STRING>'`
  
  - search index/type/query: `curl -XPOST 'localhost:9000/datashare/index/<INDEX>/<TYPE>?<QUERY_STRING>'`
        
*See [Query String syntax](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-query-string-query.html#query-string-syntax)* 

TODO: pass options as JSON       

#### Index

**`./start-idx`**

Starts an index instance on the local machine.

## Documentation

Browse the JavaDoc from **datashare/doc/index.html**


## License

DataShare is released under the [GNU General Public License](http://www.gnu.org/licenses/gpl.html)


## Feedback

We welcome feedback as well as contributions!

For any bug, question, comment or (pull) request, 

please contact us at jmartin@icij.org or julien.pierre.martin@gmail.com


## What's next

 - Test suite
 
 - Integrate Extract
 
 - Web graphical user interface
 
 - Data Sharing module
 
   - Networking module
   
   - Content Management module
     
   - User Management module
        
   - Request and Exchange Protocol
 
 