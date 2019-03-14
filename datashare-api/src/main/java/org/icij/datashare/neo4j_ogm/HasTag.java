package org.icij.datashare.neo4j_ogm;

import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.RelationshipEntity;
import org.neo4j.ogm.annotation.Property;
import org.neo4j.ogm.annotation.StartNode;
import org.neo4j.ogm.annotation.EndNode;
import org.neo4j.ogm.annotation.NodeEntity;


@RelationshipEntity(type = "HAS_TAGS")
public class HasTag {

    @Id @GeneratedValue
    private Long id;

    @StartNode
    Document document;

    @EndNode
    NamedEntity nerTag;

    @Property
    private int offset;

    public Long getId() {
        return id;
    }

    public int getOffset() { return offset; }

    public void setOffset(int offset) {
        this.offset = offset;
    }
}
