package org.asymetrik.modular.verification.testedmodules.jdkuse;

import java.net.http.HttpClient;
import java.util.List;

public class Client {

	public List<HttpClient> clients() {
		return List.of(HttpClient.newHttpClient());
	}
}
