package org.icij.datashare;

import org.icij.datashare.text.Hasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

import static org.icij.datashare.text.Hasher.SHA_384;


public interface Entity extends Serializable {
    Logger LOGGER = LoggerFactory.getLogger(Entity.class);
    Hasher DEFAULT_DIGESTER = SHA_384;

    String getId();
}
