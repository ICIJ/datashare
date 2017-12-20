//These are patterns for things that might look like sentence splits but they
//should not be used as such.
//
// Valentin Tablan, 24 Aug 2007
//
//
// Lines starting with // are comments; empty lines are ignored

//The Java RegEx matching machine is eager to return the first match
//because of this, the explicit abbreviations need to appear before the
//generic patterns so that for example "a.m." is matched in preference to
//"a.m" (which would match under the internet address rule). 


//known abbreviations
\b\.net
\b\.NET
\b\.Net
\bAG\.
\bA\.M\.
\bAPR\.
\bAUG\.
\bAdm\.
\bBrig\.
\bCO\.
\bCORP\.
\bCapt\.
\bCmdr\.
\bCo\.
\bCol\.
\bComdr\.
\bDEC\.
\bDR\.
\bDr\.
\bFEB\.
\bFig\.
\bFRI\.
\bGMBH\.
\bGen\.
\bGov\.
\bINC\.
\bJAN\.
\bJUL\.
\bJUN\.
\bLTD\.
\bLt\.
\bLtd\.
\bMAR\.
\bMON\.
\bMP\.
\bMaj\.
\bMr\.
\bMrs\.
\bMs\.
\bNA\.
\bNOV\.
\bNV\.
\bOCT\.
\bOy\.
\bPLC\.
\bP\.M\.
\bProf\.
\bRep\.
\bSA\.
\bSAT\.
\bSEP\.
\bSIR\.
\bSR\.
\bSUN\.
\bSen\.
\bSgt\.
\bSpA\.
\bSt\.
\bTHU\.
\bTHUR\.
\bTUE\.
\bVP\.
\bWED\.
\ba\.m\.
\bad\.
\bal\.
\bed\.
\beds\.
\beg\.
\be\.g\.
\bet\.
\betc\.(?!\s+\p{Upper}) 
\bfig\.
\bie\.
\bi\.e\.
\bp\.
\bp\.m\.
\busu\.
\bvs\.
\byr\.
\byrs\.

//four or more dots are ignored
\.{4,}

//five or more ?,! are ignored
(?:!|\?){5,}

//a sequence of single upper case letters followed by dot
\b(?:\p{javaUpperCase}\.)+

//numbers with decimal part or IP addresses, or Internet addresses
\p{Alnum}+(?:\.\p{Alnum}+)+

//java dotted names or Internet addresses
\p{Alpha}+(?:\.\p{Alpha}+)+
