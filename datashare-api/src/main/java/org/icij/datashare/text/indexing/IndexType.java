package org.icij.datashare.text.indexing;

/**
 * DataShare Entity Type Annotations
 *
 * Created by julien on 6/22/16.
 */
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IndexType {

    String value();

}