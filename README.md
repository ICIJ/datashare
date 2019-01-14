# User Guide for Mac

## How do I install Datashare?

1. Go to Datashare landing page \(URL to come\):

> \[screenshot of the landing page to be inserted here\]

1. Click on the button **"DOWNLOAD FOR FREE"**.
2. On your desktop:
3. Open "Finder" by clicking on the blue smiling icon in your Mac's "Dock" 
4. Open **"Downloads"** 
5. Double-click on **"Datashare.pkg"**:

> ![Double-click on Datashare.pkg](https://i.imgur.com/F1IGjsz.png)

1. You might see a window saying **"“Datashare.pkg” can’t be opened because it is from an unidentified developer."**. Click on **"OK"**:

   > ![Click on Open Anyway](https://i.imgur.com/RMlO2Kz.png)

   * If yes, click on the **Apple logo** on the top left of your computer's screen top bar.
   * Click on **"System Preferences..."**
   * Click on **"Security & Privacy"** 
   * You read at the bottom ""Datashare.pkg" was blocked from opening because it is not from an identified developer",  **click on** "Open Anyway"\*\*: 

> ![Click on Open Anyway](https://i.imgur.com/bjBNkqm.png)

1. A window untitled "Install Datashare" opened. Click 2 times on **"Continue"** and then **"Install"**.

   > ![Click on Continue](https://i.imgur.com/UMpmxBm.png) ![Click on Continue](https://i.imgur.com/bfuAGFt.png) ![Click on Install](https://i.imgur.com/ayIxDHA.png)

2. At step 3, a window saying "Installer is trying to install new software" requires your Mac's Username and Password. **Enter both** and click on **"Install Software"**.

   > ![Install Software](https://i.imgur.com/S59dV0X.png)

3. The windows says "The installation was successful. The software was installed". Click on **Close**.

   > ![Click on Close](https://i.imgur.com/nCCy5N1.png)

4. A new windows says "Do you want to move the "Datashare Installer to the Trash". You can safely click on **"Move to Trash"**.

   > ![Click on Move to Trash](https://i.imgur.com/MBC013j.png)

5. You might see a small window of "Terminal" automatically opened apart. It describes ongoing technical operations. **It will close automatically.**

   > ![It closes automatically](https://i.imgur.com/pmYgUZ1.png)

6. Datashare is now downloaded as well as another tool which makes it work: it's called **Docker**. You can find it on your Mac's menu bar, on the top right of your computer's screen. Its icons is a little whale! Docker Desktop will automatically be running when you use Datashare.

   > ![Terminal](https://i.imgur.com/fZfNLzi.png)

### How do I open Datashare?

1. Once Datashare is installed, go to **"Finder"**, then **"Applications"** and double-click on **"Datashare"**, which is a burgundy icon with "ICIJ":

   > ![Double-click on ICIJ Datashare icon](https://i.imgur.com/uTyRtCa.png)

2. **Datashare automatically opens in your default internet browser**. It if does **not** open automatically in your default browser, please type **"localhost:8080/\#/"** in your internet browser.

   > ![Datashare automatically opens](https://i.imgur.com/CyW1qhF.png)

3. It also temporarily opens a small windows in **"Terminal"** which describes the technical operations going on during the opening. **Please do not close it**. It closes automatically when it is finished or you can safely close it.

   > ![Terminal opens and closes](https://i.imgur.com/QADMxWK.png)

### How do I add documents to Datashare?

1. Open your Mac's **"Finder"** by clicking on the blue smiling icon in your Mac's "Dock".
2. On the menu top bar of your computer, click on **"Go"**.
3. Click on **"Home"** \(with the house icon\).

> ![Click on Home](https://i.imgur.com/McX4uQt.png)

1. You see **a folder called "Datashare"**.
2. **Add to this folder the documents** you want to have and to analyze on Datashare. You will be asked your computer's username and password. Enter both and click on **\*"OK"**:

> ![Click on OK](https://i.imgur.com/31QUoE9.png)

1. Go to **"Applications"** and open **Datashare** \(see above: "How do I open Datashare" \).
2. Click on **"Analyze documents"** on the top navigation bar in Datashare.

> ![Click on Analyze documents](https://i.imgur.com/OOgtMm8.png)

### How do I analyze documents?

1. Once you added documents to Datashare and you opened Datashare, click on **"Analyze documents"** on the top bar.

> ![Click on Analyze documents](https://i.imgur.com/OOgtMm8.png)

1. You're now on **"**[http://localhost:8080/\#/indexing](http://localhost:8080/#/indexing)**"**. Click on **Start a new task** if the pop-in did not open automatically.

> \[insert screenshot "How do I analyze documents\_Mac\_START.png"\]

1. Select **"Extract text"**. If you want Datashare to also detect named entites, that is to say the name of a person, an organization or a location, click on **"Find named entities"**". Then click on "Next".

> \[insert screenshot "How do I analyze documents\_Mac\_POP1.png"\] \[insert screenshot "How do I analyze documents\_Mac\_POP2.png"\]

1. In this window, click on **"Yes"** if you want to extract texts from images too \(scans, pictures, etc.\). OCR means "Optical Character Recognition" and will allow you to search terms in your files which are images.

> \[insert screenshot "How do I analyze documents\_Mac\_POP3.png"\]

1. In this window, you are asked to choose between different pipelines of Natural Language Processing. Click on "CoreNLP" if you want to use the one with the highest probability of well working in most of your documents.

> \[insert screenshot "How do I analyze documents\_Mac\_POP4.png"\]

1. You can now see running tasks and their progress. You can click on "Delete done tasks".

> \[insert screenshot "How do I analyze documents\_Mac\_RUN.png"\]

1. You can search for your indexed documents without having to wait for all tasks to be done. To access your documents, click on **"Search"**.

> \[insert screenshot "How do I analyze documents\_Mac\_SEARCH.png"\]

### How do I search documents?

1. On the homepage, type the terms you search in the search bar.
2. Type nothing and click on "Search" to have all your documents as results.

> \[insert screenshot "How do I search documents\_Mac\_SEARCH.png"\]

### What is a named entity?

A name entity is the name of a **person**, an **organization** or a **location**.

Datashare does **"Named Entity Recognition" \(NER\)**. It means that Datashare uses pipelines of Natural Language Processing \(NLP\) to automatically detect and highlight named entity in texts.

