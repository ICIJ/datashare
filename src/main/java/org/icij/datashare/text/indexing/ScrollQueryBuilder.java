package org.icij.datashare.text.indexing;


import org.icij.datashare.text.indexing.Indexer.ScrollQuery;

public class ScrollQueryBuilder {

    public String duration;
    public int numSlice;
    public int nbSlices;
    public String stringQuery;

    private ScrollQueryBuilder(){
    }

    public static ScrollQueryBuilder createScrollQuery(){
        return new ScrollQueryBuilder();
    }

    public ScrollQueryBuilder withDuration(String duration){
        this.duration = duration;
        return this;
    }
    public ScrollQueryBuilder withSlices(int numSlice, int nbSlices){
        this.numSlice = numSlice;
        this.nbSlices = nbSlices;
        return this;
    }
    public ScrollQueryBuilder withStringQuery(String stringQuery){
        this.stringQuery = stringQuery;
        return this;
    }

    public ScrollQuery build() {
        return new ScrollQuery(duration, numSlice, nbSlices, stringQuery);
    }
}
