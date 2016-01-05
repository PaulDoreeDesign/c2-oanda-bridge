package com.quas.c2obridge;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;

/**
 * Abstracts having to deal with URLs and Connections each time we want a request to be sent.
 *
 * Created by Quasar on 2/12/2015.
 */
public class Connector {

	/** UTF-8 character set */
	public static final String UTF8 = "UTF-8";

	/** GET http method */
	public static final String GET = "GET";
	/** POST http method */
	public static final String POST = "POST";
	/** DELETE http method */
	public static final String DELETE = "DELETE";
	/** PATCH http method */
	public static final String PATCH = "PATCH";

	/** The HttpURLConnection connection in this connector */
	private HttpURLConnection con;

	/** For logging purposes, URL and POST-writeStrings are tracked */
	private String urlString;
	private String writeString;

	/**
	 * Delegating constructor without any additional request properties to be set.
	 *
	 * @param urlString url
	 * @param method post, get, etc...
	 * @param apiKey Oanda API key for authorization
	 */
	public Connector(String urlString, String method, String apiKey) {
		this(urlString, method, apiKey, null);
	}

	/**
	 * Creates a URL and URLConnection with the given url string, method and Oanda API key.
	 *
	 * @param urlString url
	 * @param method post, get, etc...
	 * @param apiKey Oanda API key for authorization
	 * @param props additional request properties that need to be set
	 */
	public Connector(String urlString, String method, String apiKey, HashMap<String, String> props) {
		this.urlString = urlString;

		boolean patch = method.equals(PATCH);
		if (patch) { // method override
			method = POST;
		}

		try {
			byte[] bytes = null;
			// set all additional properties
			if (props != null) {
				String writeLine = "";
				boolean first = true;
				for (String key : props.keySet()) {
					if (!first) {
						writeLine += "&";
					}

					writeLine += URLEncoder.encode(key, UTF8) + "=" + URLEncoder.encode(props.get(key), UTF8);

					first = false;
				}

				bytes = writeLine.getBytes(UTF8);
				this.writeString = writeLine;
			}

			URL url = new URL(urlString);
			this.con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod(method);
			con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			con.setRequestProperty("Authorization", "Bearer " + apiKey);
			if (patch) {
				con.setRequestProperty("X-HTTP-Method-Override", PATCH);
				con.setRequestMethod(POST);
			}

			if (bytes != null) {
				// con.setRequestProperty("Content-Length", String.valueOf(bytes.length));
				con.setDoOutput(true);
				con.getOutputStream().write(bytes);
			}

		} catch (IOException ioe) {
			Logger.error("Error in Connector's constructor: " + ioe);
			ioe.printStackTrace(Logger.err);
			// get error stream
			BufferedReader errReader = new BufferedReader(new InputStreamReader(con.getErrorStream()));
			String errLine;
			try {
				while ((errLine = errReader.readLine()) != null) {
					Logger.error("Error message: " + errLine);
				}
			} catch (IOException ioei) {
				Logger.error("Unable to fetch error message: " + ioei);
				ioei.printStackTrace(Logger.err);
			}
		}
	}

	public String getResponse() {
		String response = "";
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				response += line + "\n";
			}
			con.getInputStream().close(); // close connection
			return response;
		} catch (IOException ioe) {
			Logger.error("Error trying to read in Connector (url = " + urlString + ", writeString = " + writeString + "): " + ioe);
			ioe.printStackTrace(Logger.err);
			return null;
		}
	}
}
