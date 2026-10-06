package org.icij.datashare.project.admin;

public class WideRoleHeldException extends Exception {
    public WideRoleHeldException(String userLogin) {
        super("user '" + userLogin + "' holds an instance or domain admin role, which already covers every project");
    }
}
