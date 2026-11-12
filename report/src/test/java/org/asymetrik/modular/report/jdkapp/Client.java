package org.asymetrik.modular.report.jdkapp;

import java.net.http.HttpClient;

public class Client {

	public HttpClient client() {
		return HttpClient.newHttpClient();
	}
}
