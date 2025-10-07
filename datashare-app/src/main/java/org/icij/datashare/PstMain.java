package org.icij.datashare;

import com.aspose.email.FolderInfo;
import com.aspose.email.MapiMessage;
import com.aspose.email.MessageInfo;
import com.aspose.email.MessageInfoCollection;
import com.aspose.email.PersonalStorage;
import com.pff.PSTAttachment;
import com.pff.PSTException;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Vector;

import static org.icij.datashare.text.StringUtils.isEmpty;

public class PstMain {
    public static void main(String[] args) {
        new PstMain("/home/dev/pst/Glenda's Keepers.pst");
    }

    public PstMain(String filename) {
        try {
            PSTFile pstFile = new PSTFile(filename);
            System.out.println(pstFile.getMessageStore().getDisplayName());
            new File("extracted").mkdirs();
            processFolder(pstFile.getRootFolder());
            System.out.printf("read :%d%n", nbMail);

        } catch (Exception err) {
            err.printStackTrace();
        }
    }

    /**
     * needs Aspose.Email library. Installed from
     * <a href="https://releases.aspose.com/java/repo/com/aspose/aspose-email/25.8/">Aspose.Email</a>
     *
     * @param filename pst file path
     */
    public static void pstMainAspose(String filename) {
        try (PersonalStorage personalStorage = PersonalStorage.fromFile(filename)) {
            // Get the list of subfolders in PST file
            FolderInfo rootFolder = personalStorage.getRootFolder();

            new File("extracted").mkdirs();
            // Traverse through all folders in the PST file
            // This is not recursive
            processFolder(rootFolder, personalStorage);
        }
    }

    public static void processFolder(FolderInfo folder, PersonalStorage personalStorage) {
        for (FolderInfo sub: folder.getSubFolders()) {
            MessageInfoCollection messageInfoCollection = sub.getContents();
            // Loop through all the messages in this folder
            for (MessageInfo messageInfo : messageInfoCollection) {
                // Extract the message in MapiMessage instance
                MapiMessage message = personalStorage.extractMessage(messageInfo);

                System.out.println("Saving message " + message.getSubject() + " ...");

                // Save the message to disk in MSG format
                // TODO: File name may contain invalid characters [\ / : * ? " < > |]
                message.save("extracted/" + message.getInternetMessageId() + ".msg");
            }
            processFolder(sub, personalStorage);
        }
    }

    int nbMail = 0;
    int depth = -1;

    public void processFolder(PSTFolder folder)
            throws PSTException, java.io.IOException, MessagingException {
        depth++;
        // the root folder doesn't have a display name
        if (depth > 0) {
            printDepth();
            System.out.println(folder.getDisplayName());
        }

        // go through the folders...
        if (folder.hasSubfolders()) {
            Vector<PSTFolder> childFolders = folder.getSubFolders();
            for (PSTFolder childFolder : childFolders) {
                processFolder(childFolder);
            }
        }

        // and now the emails for this folder
        if (folder.getContentCount() > 0) {
            depth++;
            PSTMessage email = (PSTMessage) folder.getNextChild();
            while (email != null) {
                printDepth();
                System.out.println("Email: " + email.getSubject());
                Path tempDirectory = Files.createTempDirectory("datashare-pst");
                tempDirectory.toFile().deleteOnExit();
                Message emlMessage = createMessage(email.getSenderEmailAddress(), email.getOriginalDisplayTo(),
                            email.getOriginalDisplayCc(), email.getOriginalDisplayBcc(), email.getSubject(),
                            email.getBody(), getAttachments(email, tempDirectory));
                emlMessage.writeTo(new FileOutputStream(String.format("%d.eml", email.getDescriptorNodeId())));
                email = (PSTMessage) folder.getNextChild();
                nbMail++;
            }
            depth--;
        }
        depth--;
    }

    public Message createMessage(String from, String to, String cc, String bcc, String subject, String body, List<File> attachments) throws MessagingException {
        Message message = new MimeMessage(Session.getInstance(System.getProperties()));
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
        message.setSubject(subject);

        MimeBodyPart content = new MimeBodyPart();
        content.setText(body);
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(content);

        for (File file : attachments) {
            MimeBodyPart attachment = new MimeBodyPart();
            DataSource source = new FileDataSource(file);
            attachment.setDataHandler(new DataHandler(source));
            attachment.setFileName(file.getName());
            multipart.addBodyPart(attachment);
        }
        message.setContent(multipart);
        return message;
    }

    List<File> getAttachments(PSTMessage email, Path tempDirectory) throws PSTException, IOException {
        int numberOfAttachments = email.getNumberOfAttachments();
        List<File> files = new LinkedList<>();
        for (int x = 0; x < numberOfAttachments; x++) {
            PSTAttachment attach = email.getAttachment(x);
            InputStream attachmentStream = attach.getFileInputStream();
            String filename = attach.getLongFilename();
            if (filename.isEmpty()) {
                filename = attach.getFilename();
            }
            File file = tempDirectory.resolve(isEmpty(filename)?generateString(new Random(), 16):filename).toFile();
            FileOutputStream out = new FileOutputStream(file);
            int bufferSize = 8176;
            byte[] buffer = new byte[bufferSize];
            int count = attachmentStream.read(buffer);
            while (count == bufferSize) {
                out.write(buffer);
                count = attachmentStream.read(buffer);
            }
            byte[] endBuffer = new byte[count];
            System.arraycopy(buffer, 0, endBuffer, 0, count);
            out.write(endBuffer);
            out.close();
            attachmentStream.close();
            files.add(file);
        }
        return files;
    }

    static String characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public static String generateString(Random rng, int length) {
        char[] text = new char[length];
        for (int i = 0; i < length; i++) {
            text[i] = characters.charAt(rng.nextInt(characters.length()));
        }
        return new String(text);
    }


    public void printDepth() {
        for (int x = 0; x < depth - 1; x++) {
            System.out.print(" | ");
        }
        System.out.print(" |- ");
    }
}
