package org.icij.datashare.user.admin;

public class UserExistsException extends Exception {
    public UserExistsException(String login) {
        super("user '" + login + "' already exists");
    }
}
