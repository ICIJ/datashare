package org.icij.datashare.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Prompter {
    public static class ValidationFailedException extends RuntimeException {
        private final String field;
        public ValidationFailedException(String field, String message) {
            super(message);
            this.field = field;
        }
        public String field() { return field; }
    }

    static final int MAX_RETRIES = 3;

    private final BufferedReader in;
    private final PrintWriter out;
    private final Supplier<char[]> passwordSupplier;

    public Prompter() {
        this(new BufferedReader(new InputStreamReader(System.in)),
             new PrintWriter(System.err, true),
             () -> System.console() == null ? new char[0] : System.console().readPassword());
    }

    public Prompter(BufferedReader in, PrintWriter out, Supplier<char[]> passwordSupplier) {
        this.in = in;
        this.out = out;
        this.passwordSupplier = passwordSupplier;
    }

    public String promptString(String label, Consumer<String> validator) {
        Validators.InvalidValueException lastError = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            out.print(label + ": ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                throw new ValidationFailedException("io", e.getMessage());
            }
            if (line == null) line = "";
            try {
                validator.accept(line);
                return line;
            } catch (Validators.InvalidValueException e) {
                lastError = e;
                out.println("invalid: " + e.getMessage());
            }
        }
        throw new ValidationFailedException(lastError.field(), lastError.getMessage());
    }

    public String promptPassword() {
        for (int i = 0; i < MAX_RETRIES; i++) {
            out.print("Password: ");
            out.flush();
            char[] firstEntry = passwordSupplier.get();
            out.print("Password (confirm): ");
            out.flush();
            char[] confirmation = passwordSupplier.get();
            try {
                if (firstEntry != null && confirmation != null
                        && firstEntry.length > 0
                        && Arrays.equals(firstEntry, confirmation)) {
                    return new String(firstEntry);
                }
                out.println("invalid: passwords do not match or are empty");
            } finally {
                // Wipe char[] copies so the password does not linger in memory.
                if (firstEntry != null) Arrays.fill(firstEntry, '\0');
                if (confirmation != null) Arrays.fill(confirmation, '\0');
            }
        }
        throw new ValidationFailedException("password",
                "password entry failed after " + MAX_RETRIES + " attempts");
    }

    public boolean confirm(String label) {
        out.print(label + " [y/N]: ");
        out.flush();
        try {
            String line = in.readLine();
            return line != null && !line.isEmpty()
                    && (line.charAt(0) == 'y' || line.charAt(0) == 'Y');
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isInteractive() {
        return System.console() != null;
    }
}
