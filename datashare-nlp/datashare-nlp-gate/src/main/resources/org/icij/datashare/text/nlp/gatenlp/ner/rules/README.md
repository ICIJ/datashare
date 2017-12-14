# JAPE RULES
<https://gate.ac.uk/sale/tao/splitch8.html#chap:jape>


These folder contains the jape rules that are executed over the documents. Each file contains a particular rule or set of rules to identify a specific entity.  

The file "main.jape" contains the set of rules that are going to be loaded in the application in the order defined.  The order is important to have entities been recognised after particular jape rules are executed.

## PERSON 
The file "person.jape" contains three rules to identify names of persons. The control is in appelt mode, so only the rule with the longest match will be considered.

All the rules base their recognition in the person title recognised previously by the gazetteers  

The first rule (PersonPatter1) tries to identify words that start with capital letters after a title. (e.g., Marius Kohl)  

The second rule (PersonPatter2) allows abbreviations in the words that composes the name (e.g., Eric L. Broussard, B. Borg) and  mixed capital letters (e.g., James McCluney).  

The thrid rule (PersonPattern3) is made to identify persons which surnames are in capital. (e.g., Marc PECQUET)

- The main restriction of these rules is that is not allowed to identify the entity in different lines. This will be considered as a break point. There are many cases as below:  
    Mr. Marius Khol   
    Administration ......  
The rule must be stopped in the new line.  

However could be cases when some information is lost:

Corporation), Mr. Dennis S. Adams (Assistant Treasurer of BDC), Mr. Charles E.
Fenton (General Counsel to BDC), Mr. Michael D. Mangan (Chief Financial Officer of
BDC) and Mr. Paul F. Mc Bride (President - Power Tools and Accessories of BDC).

## TELEPHONE
The file "telephone.jape" contains two rules to identify telephones inside the document.

The rules base the recognition when a token "+" and a country telephone preffix are found. The country telephone preffix is recognised previously by the gazetteers
The rules will consider part of the telephone entity numbers and white spaces
The rules do not control the minium number of digits recognised. This has to be done in other process (EntityOutput plugin). This restriction is because there is no way to control the white spaces that are going to appear.

The TelephonePattern pattern considers that the telephone prefix is in a separately token from the telephone. However, there are other cases that the telephone is presented together. TelephonePattern2 considers that restriction.  


## TELEPHONE EXTENDED
The file "telephoneExtended.jape" contains the rule to identify telephone extensions inside the document.
The rules is based in the pattern TelephonePattern from "telephone.jape". However, it must contain the symbol "-" and at least one number more.


## EMAIL
The file "email.jape" contains the rule that identifies the entity in the documents. The rule is based in the identification of the "@" and in a real email domain recognised previously by the gazetteers (e.g., org, net, etc.). No spaces are allowed inside the entity.  

the user name of the email could contain number, words and punctuation:  p.calleja23, p_calleja, p-calleja  
the domain allows only words and dots.  

## STREET
There are two files that contain different rules for street/address detection. 

"Street1.jape" bases the recognition in the word rue/Rue. The address could contain a number and a comma before it and the adrress detection must finish with a country.

"Street2.jape" contains a more general rule for the address detection. The rule is based in the detection of places annotated by gazeetters (street, square, st. ...), and like the other, the detection must stop with a country.
## COUNTRY
The file "country.jape" contains the rule to transform the annotations marked by the gazeetteers as countries (Lookup.country) into an own annotation. The rules also consider that if a variant of EEUU or UK has been detected by the gazetteers, the country annotation must show United States and United Kingdom as result. 


## COMPANY
There are four jape files to identify companies. Two of them are involved in the identification of company suffixes and the other two to identify the entity.

"CompanySuffixLookup.jape" contains the rule that transforms the componay suffixes identified by the gazetteer with annotation "lookup" to the annotation "CompanySuffix".
"CompanySuffix1.jape" contains the rule to identify the suffix Sarl with OCR errors. (e.g., S.ar.l, S.ar.f, s.ar1,etc.)
"Company1.jape". Once the suffixes are identified. The words words before a suffix are identified as a company (the suffix is included). These words must start or contain capital letters. There rule also allows words as "No","de" or "and" and punctuation marks as "/" or "(".
"Company2.jape" contains the another rule to identify companies entities. If the words "distributed by" is identified, the next words are annotated as a company.







