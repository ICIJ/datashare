package org.icij.datashare.batch;

public class SearchException extends RuntimeException {
    public final String query;

    public SearchException(String query, Throwable root) {
        super(root);
        this.query = query;
    }

    @Override
    public String toString() {
        return "SearchException: query='" + query + "' message='" + super.toString() + "'";
    }
}
