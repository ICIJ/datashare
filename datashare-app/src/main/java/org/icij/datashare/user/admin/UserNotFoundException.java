package org.icij.datashare.user.admin;

public class UserNotFoundException extends Exception {
    public UserNotFoundException(String login) {
        super("user '" + login + "' not found");
    }
}
