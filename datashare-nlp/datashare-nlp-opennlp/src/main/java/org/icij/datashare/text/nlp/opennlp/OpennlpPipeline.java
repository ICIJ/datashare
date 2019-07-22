package org.icij.datashare.text.nlp.opennlp;

import com.google.inject.Inject;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.opennlp.models.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.stream.Collectors.toList;
import static opennlp.tools.util.Span.spansToStrings;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * {@link Pipeline}
 * {@link AbstractPipeline}
 * {@link Type#OPENNLP}
 *
 * <a href="https://opennlp.apache.org/">Apache OpenNLP</a>
 * Models v1.5
 * <a href="http://opennlp.sourceforge.net/models-1.5/">English</a>,
 * <a href="http://opennlp.sourceforge.net/models-1.5/">Spanish</a> (English used for TOKEN and SENTENCE),
 * <a href="http://opennlp.sourceforge.net/models-1.5/">German</a>,
 * <a href="https://sites.google.com/site/nicolashernandez/resources/opennlp">French</a>  (English used for NER)
 *
 * Created by julien on 3/29/16.
 */
public final class OpennlpPipeline extends AbstractPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, NER)));
                put(SPANISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, NER)));
                put(FRENCH,  new HashSet<>(asList(SENTENCE, TOKEN, POS, NER)));
                put(GERMAN,  new HashSet<>(asList(SENTENCE, TOKEN, POS)));
            }};

    Map<Language, SentenceDetector> sentencer;
    Map<Language, Tokenizer> tokenizer;
    Map<Language, POSTagger> posTagger;
    Map<Language, List<NameFinderME>> nerFinder;

    @Inject
    public OpennlpPipeline(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());

        // SENTENCE <-- TOKEN <-- {POS, NER}
        stageDependencies.get(TOKEN).add(SENTENCE);
        stageDependencies.get(POS)  .add(TOKEN);
        stageDependencies.get(NER)  .add(TOKEN);

        sentencer = new HashMap<>();
        tokenizer = new HashMap<>();
        posTagger = new HashMap<>();
        nerFinder = new HashMap<>();
    }

    @Override
    public Map<Language, Set<NlpStage>> supportedStages() { return SUPPORTED_STAGES; }

    @Override
    public boolean initialize(Language language) throws InterruptedException {
        if (!super.initialize(language)) {
            return false;
        }
        HashMap<NlpStage, Function<Language, Boolean>> annotatorLoader = new HashMap<NlpStage, Function<Language, Boolean>>() {{
            put(TOKEN, logIfInterrupted(OpennlpPipeline.this::loadTokenizer));
            put(SENTENCE, logIfInterrupted(OpennlpPipeline.this::loadSentenceDetector));
            put(POS, logIfInterrupted(OpennlpPipeline.this::loadPosTagger));
            put(NER, logIfInterrupted(OpennlpPipeline.this::loadNameFinder));
        }};
        stages.forEach(stage -> annotatorLoader.get(stage).apply(language));
        return true;
    }

    @Override
    public Annotations process(String content, String docId, Language language) {
        Annotations annotations = new Annotations(docId, getType(), language);
        String annotators = "SENTENCING ~ TOKENIZING";
        if (targetStages.contains(POS))
            annotators += " ~ POS-TAGGING";
        if (targetStages.contains(NER))
            annotators += " ~ NAME-FINDING";
        LOGGER.info(annotators + " for " + language);

        // Split input into sentences
        Span[] sentenceSpans = sentences(content, language);
        for (Span sentenceSpan : sentenceSpans) {
            // Feed annotations
            int sentenceOffsetBegin = sentenceSpan.getStart();
            int sentenceOffsetEnd   = sentenceSpan.getEnd();
            annotations.add(SENTENCE, sentenceOffsetBegin, sentenceOffsetEnd);

            // Tokenize sentence
            String sentence = sentenceSpan.getCoveredText(content).toString();
            Span[] sentenceTokenSpans = tokenize(sentence, language);
            String[] sentenceTokens  = spansToStrings(sentenceTokenSpans, sentence);

            // Pos-tag sentence
            String[] sentencePosTags = new String[0];
            if (targetStages.contains(POS)) {
                sentencePosTags = postag(sentenceTokens, language);
            }

            // Feed annotations with token and pos
            for (Span sentenceTokenSpan : sentenceTokenSpans) {
                int tokenOffsetBegin = sentenceOffsetBegin + sentenceTokenSpan.getStart();
                int tokenOffsetEnd   = sentenceOffsetBegin + sentenceTokenSpan.getEnd();
                annotations.add(TOKEN, tokenOffsetBegin, tokenOffsetEnd);
                if (targetStages.contains(POS)) {
                    String pos = spanToString(sentenceTokenSpan, sentencePosTags);
                    annotations.add(POS, tokenOffsetBegin, tokenOffsetEnd);
                }
            }

            // NER on sentence
            if (targetStages.contains(NER)) {
                for (NameFinderME nameFinderME : nerFinder.get(language)) {
                    Span[] nerSpans = nameFinderME.find(sentenceTokens);
                    for (Span nerSpan : nerSpans) {
                        int nerStart = sentenceOffsetBegin + sentenceTokenSpans[nerSpan.getStart()].getStart();
                        int nerEnd   = sentenceOffsetBegin + sentenceTokenSpans[nerSpan.getEnd()-1].getEnd();
                        annotations.add(NER, nerStart, nerEnd, NamedEntity.Category.parse(nerSpan.getType()));
                    }
                }
            }
        }
        return annotations;
    }

    private String spanToString(Span span, String[] elements) {
        return String.join(" ", asList(copyOfRange(elements, span.getStart(), span.getEnd())));
    }

    @Override
    public void terminate(Language language) throws InterruptedException {
        super.terminate(language);

        if (nerFinder.containsKey(language)) {
            nerFinder.get(language).forEach(NameFinderME::clearAdaptiveData);
        }

        // (Don't) keep models in memory
        if ( ! caching) {
            sentencer.remove(language);
            tokenizer.remove(language);
            posTagger.remove(language);
            nerFinder.remove(language);
        }
    }

    @FunctionalInterface
    interface Interruptible<P, R, E extends Throwable> {
        R apply(P t) throws E;
    }

    public Function<Language, Boolean> logIfInterrupted(Interruptible<Language, Boolean, InterruptedException> fun) {
        return val -> {
            try {
                return fun.apply(val);
            } catch (InterruptedException e) {
                LOGGER.error("interrupted", e);
                return false;
            }
        };
    }

    private Boolean loadTokenizer(Language language) throws InterruptedException {
        if ( tokenizer.containsKey(language) )
            return true;
        ArtifactProvider model = OpenNlpTokenModels.getInstance().get(language);
        tokenizer.put(language, new TokenizerME((TokenizerModel) model));
        return true;
    }

    private boolean loadSentenceDetector(Language language) throws InterruptedException {
        if (sentencer.containsKey(language))
            return true;
        ArtifactProvider model = OpenNlpSentenceModels.getInstance().get(language);
        sentencer.put(language, new SentenceDetectorME((SentenceModel) model));
        return true;
    }

    private boolean loadPosTagger(Language language) throws InterruptedException {
        if ( posTagger.containsKey(language) )
            return true;
        ArtifactProvider model = OpenNlpPosModels.getInstance().get(language);
        posTagger.put(language, new POSTaggerME((POSModel) model));
        return true;
    }

    private boolean loadNameFinder(Language language) throws InterruptedException {
        OpenNlpCompositeModel nerModels = (OpenNlpCompositeModel) OpenNlpNerModels.getInstance().get(language);
        final Stream<NameFinderME> nameFinderMEStream =
                nerModels.models.stream().map(m -> new NameFinderME((TokenNameFinderModel) m));
        nerFinder.put(language, nameFinderMEStream.collect(toList()));
        return true;
    }

    private Span[] sentences(String input, Language language) {
        if (!stages.contains(SENTENCE) || !sentencer.containsKey(language))
            return new Span[0];
        return sentencer.get(language).sentPosDetect(input);
    }

    private String[] postag(String[] tokens, Language language) {
        if (!stages.contains(POS) || !posTagger.containsKey(language))
            return new String[0];
        return posTagger.get(language).tag(tokens);
    }

    private Span[] tokenize(String input, Language language) {
        if (!stages.contains(TOKEN) || !tokenizer.containsKey(language))
            return new Span[0];
        return tokenizer.get(language).tokenizePos(input);
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(OpenNlpPosModels.POS_TAGSET.get(language));
    }
}

