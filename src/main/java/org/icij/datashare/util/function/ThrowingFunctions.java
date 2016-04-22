package org.icij.datashare.util.function;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntityCategory;
import org.icij.datashare.text.processing.NLPStage;


/**
 * Utility functions class
 *
 * Created by julien on 4/21/16.
 */
public class ThrowingFunctions {

    public static final ThrowingFunction<String, ThrowingFunction<String, List<String>>> split = val ->
            (str) -> Arrays.asList(str.split(val));

    public static final ThrowingFunction<String, List<String>> splitComma     = split.apply(",");

    public static final ThrowingFunction<String, List<String>> splitSemicolon = split.apply(";");

    public static final ThrowingFunction<String, List<String>> splitColon     = split.apply(":");


    public static final ThrowingFunction<String, ThrowingFunction<String, String>> remove = pttrn ->
            (str) -> str.replaceAll(pttrn, "");

    public static final ThrowingFunction<String, String> removeSpaces = remove.apply("(\\s+)");


    public static final ThrowingFunction<String, String> trim = String::trim;


    public static final ThrowingFunction<String, Language> parseLanguage = Language::parse;

    public static final ThrowingFunction<String, Boolean>  parseBoolean  = Boolean::parseBoolean;

    public static final ThrowingFunction<String, Integer>  parseInt = Integer::parseInt;

    public static final ThrowingFunction<String, Charset>  parseCharset  = Charset::forName;


    public static final ThrowingFunction<List<String>, Set<NamedEntityCategory>> parseEntities = lst -> lst
            .stream()
            .map(NamedEntityCategory::parse)
            .collect(Collectors.toSet());

    public static final ThrowingFunction<List<String>, List<NLPStage>> parseStages = lst -> lst
            .stream()
            .map(NLPStage::parse)
            .collect(Collectors.toList());

    public static final ThrowingFunction<String, ThrowingFunction<List<?>, String>> joinList = sep ->
            (lst) -> String.join(sep, lst
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toList()));
    public static final ThrowingFunction<List<?>, String> joinListComma = joinList.apply(",");

}
