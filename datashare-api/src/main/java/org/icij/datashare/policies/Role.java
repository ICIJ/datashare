package org.icij.datashare.policies;

public enum Role {
    INSTANCE_ADMIN,
    DOMAIN_ADMIN,
    PROJECT_ADMIN,
    PROJECT_EDITOR,
    PROJECT_MEMBER,
    PROJECT_VISITOR
}


//
//alice,*,delete
//alice,  domain:domA,manage
//alice,domain:domB|proj:p3,    write
//bob, domain:domA,            delete
//bob, domain:domA|proj:p1,    write
//bob,domain:domB,            read
//bob,domain:domB|proj:p3,    read
//carol,domain:domA|proj:p1,    delete
//carol,domain:domA|proj:p2,    manage
//carol,  domain:domB|proj:p1,    read
//carol,  domain:domA,delete
//dave,   domain:domA|proj:p1,    read
//dave,   domain:domA|proj:p1,    write
//dave,   domain:domA|proj:p1,    delete
//dave,   domain:domA|proj:p2,    write
//eve,    domain:domA|proj:p1,    read
//eve,    domain:domA|proj:p1,    comment
//eve,    domain:domA|proj:p1,    write
//frank,  domain:domA|proj:p1,    read
//frank,  domain:domA|proj:p1,    comment