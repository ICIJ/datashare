package org.icij.datashare.batch;


import java.util.List;

public class WebQueryBuilder {
    private int size;
    private int from;
    private String sort;
    private String query;
    private String field;
    private String order;
    private List<String> queries;
    private boolean queriesExcluded;
    private List<String> contentTypes;
    private List<String> project;
    private List<String> batchDate;
    private List<String> state;
    private String publishState;
    private boolean withQueries;

    private WebQueryBuilder(){}


    public static WebQueryBuilder createWebQuery(){
        return new WebQueryBuilder();
    }
    public static WebQueryBuilder createWebQuery(String query, String field){
        return new WebQueryBuilder().withRange(0,0).withQuery(query).withField(field);
    }

    public WebQueryBuilder queryAll(){
        this.query = "*";
        this.field = "all";
        return this;
    }
    public WebQueryBuilder withRange(int from, int size){
        this.from = from;
        this.size = size;
        return this;
    }
    public WebQueryBuilder withQuery(String query){
        this.query = query;
        return this;
    }
    public WebQueryBuilder withField(String field){
        this.field = field;
        return this;
    }
    public WebQueryBuilder withSortOrder(String sort, String order){
        this.sort = sort;
        this.order = order;
        return this;
    }
    public WebQueryBuilder withQueries(List<String> queries){
        this.queries = queries;
        return this;
    }
    public WebQueryBuilder withBatchDate(List<String> batchDate){
        this.batchDate = batchDate;
        return this;
    }
    public WebQueryBuilder withProjects(List<String> projects){
        this.project = projects;
        return this;
    }
    public WebQueryBuilder withState(List<String> state){
        this.state = state;
        return this;
    }
    public WebQueryBuilder withPublishState(String publishDate){
        this.publishState = publishDate;
        return this;
    }
    public WebQueryBuilder withContentTypes(List<String> contentTypes){
        this.contentTypes = contentTypes;
        return this;
    }

    public WebQueryBuilder queriesExcluded(boolean queriesExcluded){
        this.queriesExcluded = queriesExcluded;
        return this;
    }
    public WebQueryBuilder queriesRetrieved(boolean withQueries){
        this.withQueries = withQueries;
        return this;
    }
    public BatchSearchRepository.WebQuery build() {
        return new BatchSearchRepository.WebQuery(size,from,sort,order,query,field,
                queries, project, batchDate,state ,publishState,withQueries,queriesExcluded,contentTypes);
    }
}
