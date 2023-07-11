package org.icij.datashare.com.mail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.auth.LoginFailedException;
import org.subethamail.smtp.auth.UsernamePasswordValidator;
import org.subethamail.wiser.Wiser;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class MailSenderTest {
	private Wiser fakeSmtpServer;
	private static final int testSmtpPort = 2500;
	private MailSender sender;

	@Before public void setUp() {
		fakeSmtpServer = new Wiser();
		fakeSmtpServer.setPort(testSmtpPort);
		fakeSmtpServer.start();
		sender = new MailSender("localhost", testSmtpPort);
	}

	@After public void tearDown() throws InterruptedException {
		fakeSmtpServer.stop();
		Thread.sleep(100);
	}

	@Test public void sendSimpleMail() throws Exception {
		Mail mail = new Mail("from", "recipient@fake.net", "subject", "body");

		sender.send(mail);

		assertEquals(1, fakeSmtpServer.getMessages().size());
		Mail receivedMail = extractMail(fakeSmtpServer.getMessages().get(0).getMimeMessage());
		assertEquals(mail.toString(), receivedMail.toString());
	}

	@Test public void sendSimpleMailWithUserPass() throws Exception {
		UsernamePasswordValidator validator = new RequiredUsernamePasswordValidator("user", "password");
		fakeSmtpServer.getServer().setAuthenticationHandlerFactory(new EasyAuthenticationHandlerFactory(validator));
		fakeSmtpServer.getServer().setRequireAuth(true);
		MailSender passSender = new MailSender("localhost", testSmtpPort, "user", "password", false, false);
		Mail mail = new Mail("from", "recipient@fake.net", "subject", "body");

		passSender.send(mail);

		assertEquals(1, fakeSmtpServer.getMessages().size());
	}

	@Test public void sendSimpleMailWithUserPassUrl() throws Exception {
		MailSender passSender = new MailSender(new URI("smtp://user:password@host:12345"));

		assertThat(passSender.user).isEqualTo("user");
		assertThat(passSender.password).isEqualTo("password");
		assertThat(passSender.port).isEqualTo(12345);
		assertThat(passSender.host).isEqualTo("host");
	}

	@Test public void sendSimpleMailWithSimpleUrl() throws Exception {
		MailSender passSender = new MailSender(new URI("smtp://host:12345"));

		assertThat(passSender.user).isNull();
		assertThat(passSender.password).isNull();
		assertThat(passSender.tls).isFalse();
	}

	@Test public void sendSimpleMailWithSimpleUrlWithSsl() throws Exception {
		MailSender passSender = new MailSender(new URI("smtps://host:12345"));
		assertThat(passSender.tls).isTrue();
	}

	@Test public void sendSimpleMailWithSimpleUrAndDebug() throws Exception {
		MailSender passSender = new MailSender(new URI("smtp://host:12345?debug=true"));
		assertThat(passSender.debug).isTrue();
	}

	@Test public void sendMailWithCC() throws Exception {
		Mail mail = new Mail("from", new ArrayList<String>(), singletonList("recipient@fake.net"), "subject", "body");

		sender.send(mail);

		assertEquals(1, fakeSmtpServer.getMessages().size());

		Mail receivedMail = extractMail(fakeSmtpServer.getMessages().get(0).getMimeMessage());

		assertEquals(mail.toString(), receivedMail.toString());
	}

	@Test public void send_mail_without_dest_throws_exception() {
		try {
			sender.send(new Mail("from", null, null, "subject", "body"));
			fail("Cannot send mail without recipient");
		} catch (MailException me) {
			assertEquals(SendFailedException.class, me.getCause().getClass());
		}
	}

	private Mail extractMail(Message message) throws MessagingException, IOException {
		String from, subject;
		List<String> recipients, recipientsCC;
		String content;
		from = message.getFrom()[0].toString();
		subject = message.getSubject();
		recipients = new ArrayList<>();
		recipientsCC = new ArrayList<>();

		if (message.getRecipients(TO) != null) {
			for (Address adresse : message.getRecipients(TO)) {
				recipients.add(adresse.toString());
			}
		}

		if (message.getRecipients(CC) != null) {
			for (Address adresse : message.getRecipients(CC)) {
				recipientsCC.add(adresse.toString());
			}
		}
		content = (String) message.getContent();

		return new Mail(from, recipients, recipientsCC, subject, content);
	}

	private static class RequiredUsernamePasswordValidator implements UsernamePasswordValidator {
		private final String user;
		private final String pass;

		public RequiredUsernamePasswordValidator(String user, String pass) {
			this.user = user;
			this.pass = pass;
		}

		public void login(String username, String password) throws LoginFailedException {
			if (!username.equals(user) || !password.equals(pass)) {
				throw new LoginFailedException();
			}
		}
	}

}
