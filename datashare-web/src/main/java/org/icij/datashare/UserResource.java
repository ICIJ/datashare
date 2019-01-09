package org.icij.datashare;

import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.session.HashMapUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Prefix("/api/user")
public class UserResource {
    private Logger logger = LoggerFactory.getLogger(getClass());


    @Get("/indices")
    public List<String> getIndices(Context context) {
        return ((HashMapUser)context.currentUser()).getIndices();
    }

}
