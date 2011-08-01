/*******************************************************************************
 * Copyright (c) 2010-02-16 Intuit Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.opensource.org/licenses/eclipse-1.0.php
 * Contributors:
 *
 *    Manish Shah
 *    Clinton Nielsen
 *    Gopi Nambiar
 *
 ******************************************************************************/

package com.txtweb.wikipedia;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.WordUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;

import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.OutputDocument;
import net.htmlparser.jericho.Source;

public class Wikipedia extends HttpServlet {

	private static final long serialVersionUID = -3804644134777107568L;

	private static final String APPKEY_NAME = "txtweb-appkey";
	private static final String APPKEY_CONTENT = "DF43463A-66D8-40CE-B2DA-22C95B812701";
	
	private static final String CSS_TXTWEB_FORM = "txtweb-form";
	
	private static final String HTTP_PARAM_PAGE = "page";
	private static final String HTTP_PARAM_TXTWEB_MESSAGE = "txtweb-message";
	private static final String HTTP_PARAM_PARAGRAPH_NUMBER = "paragraph-number";
	
	public void doGet(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException {

		String txtWebMessageParam = httpRequest.getParameter(HTTP_PARAM_TXTWEB_MESSAGE);
		String pageParam = httpRequest.getParameter(HTTP_PARAM_PAGE);
		String paragraphNumberParam = httpRequest.getParameter(HTTP_PARAM_PARAGRAPH_NUMBER);
		
		String page = "";
		if(pageParam != null && !pageParam.isEmpty()) {
			// If we know what page to display, then disregard the user's message 
			//	and simply display the page.
			page = pageParam;
		} else if (txtWebMessageParam != null && !txtWebMessageParam.isEmpty()){
			// Format the user's message to conform to wikipedia's URL naming conventions
			page = txtWebMessageParam;
			page = page.toLowerCase(); 
			page = WordUtils.capitalize(page);
			page = page.replaceAll(" ", "_");
		} else {
			// We don't know what page to display, and the user didn't send any message
			//	Respond with a welcome message and instructions on how to use the service
			String response = getWelcomeMessage();
			sendResponse(httpResponse, response);
			return;
		}
		
		int paragraphNumber = 1;    
		if(paragraphNumberParam != null) {
			try{
				paragraphNumber = Integer.parseInt(paragraphNumberParam);
			} catch (NumberFormatException e) {}
		}

		HttpClient httpclient = new DefaultHttpClient();
		try{
			page = URLEncoder.encode(page, "UTF-8");
			HttpGet httpGet = new HttpGet("http://en.wikipedia.org/wiki/" + page); 
			
			ResponseHandler<String> responseHandler = new BasicResponseHandler();
			String responseBody = httpclient.execute(httpGet, responseHandler);
			
			Source source = new Source(responseBody);
			Element bodyContent = source.getElementById("bodyContent");
			String response = "";
			if(bodyContent != null) {
				response = parseHtmlNode(bodyContent, page, paragraphNumber);
			}

			if(!response.isEmpty()) {
				sendResponse(httpResponse, response);	
				return;
			}
			
		} catch (MalformedURLException e) {
		} catch (IOException e) {
		} finally {
			httpclient.getConnectionManager().shutdown();
		}

		if(pageParam != null && !pageParam.isEmpty()) {
			// Unknown error or no results. Respond with a nothing found message
			// and instructions on how to use the service.
			String response = getNothingFoundMessage(pageParam);
			sendResponse(httpResponse, response);
			return;
		}

		if(txtWebMessageParam != null && !txtWebMessageParam.isEmpty()) {
			// Unknown error or no results. Respond with a nothing found message 
			// and instructions on how to use the service.
			String response = getNothingFoundMessage(txtWebMessageParam);
			sendResponse(httpResponse, response);
			return;
		}
		
		// Unknown error or no results. Respond with a welcome message 
		// and instructions on how to use the service.
		String response = getWelcomeMessage();
		sendResponse(httpResponse, response);
		return;
	}

	private String parseHtmlNode(Element theElement, String page, int paragraphNumToDisplay) {
		String response = "";

		List<Element> children = (List<Element>)theElement.getChildElements();
		int currentParagraphNum = 0;
		for (Element child: children) {
			if (child.getName().equals("p")) {
				currentParagraphNum++;
				if(currentParagraphNum == paragraphNumToDisplay) {
					// This is the correct paragraph. Add it to our response. 
					Source paragraphSource = new Source(child.getContent().toString());
					OutputDocument outputDocument = new OutputDocument(paragraphSource);
					for(Element el : paragraphSource.getChildElements()) {
						outputDocument = parseHtmlNodeRecurse(outputDocument, el);
					}
					response = outputDocument.toString();
				} else if(currentParagraphNum > paragraphNumToDisplay) {
					// This is the following paragraph. Insert a link so the user can get to it, and break.
					response += "<br/><br/>";
					response += "<a href='/wikipedia" 
						+ "?" + HTTP_PARAM_PAGE + "=" + page 
						+ "&" + HTTP_PARAM_PARAGRAPH_NUMBER + "=" + (paragraphNumToDisplay + 1) + "' "
						+ " class='txtweb-menu-for' accesskey='M'>more</a>";	// Reply M for more
					break;
				}
			}
			else if (child.getName().equals("ul") || child.getName().equals("ol")) {
				if (currentParagraphNum == paragraphNumToDisplay) {
					// Include lists with the preceding paragraph.
					Source paragraphSource = new Source(child.getContent().toString());
					OutputDocument outputDocument = new OutputDocument(paragraphSource);
					for(Element el : paragraphSource.getChildElements()) {
						outputDocument = parseHtmlNodeRecurse(outputDocument, el);
					}
					response += outputDocument.toString();
				}
			}
		}

		if(response.isEmpty()) {
			try {
				response = getNothingFoundMessage(URLDecoder.decode(page, "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				response = getWelcomeMessage();
			}
		}
		return response;
	}

	// Parse the elements recursively removing external links and superscripts
	private OutputDocument parseHtmlNodeRecurse(OutputDocument outputDocument, Element element) {
		if(element.getName().equalsIgnoreCase("a")) {
			String href = element.getAttributeValue("href");
			if(href != null && href.toLowerCase().startsWith("/wiki/")) {
				// Replace the link with one of our own
				href = "/wikipedia"
					+ "?" + HTTP_PARAM_PAGE + "=" + href.substring("/wiki/".length()); 
   				outputDocument.replace(element, "<a href='" + href + "' >" + element.getContent().toString() + "</a>");
			} else {
				// Remove external links
				outputDocument.replace(element, element.getContent().toString());
			}
		} else if(element.getName().equalsIgnoreCase("sup")) {
	 		// Remove superscripts
			outputDocument.replace(element, "");
		} else if(element.getAttributeValue("class") != null && element.getAttributeValue("class").equals("IPA")) {			
	 		// Remove Phonetic links
			outputDocument.replace(element, "");
		} else {
			if(element.getChildElements() != null) {
				for(Element childElement : element.getChildElements()) {
					outputDocument = parseHtmlNodeRecurse(outputDocument, childElement);
				}
			}
		}
		return outputDocument;
	}
	
	private String getWelcomeMessage() {
		return "Welcome to Wikipedia<br/><br/>"
			+ getSearchForm();
	}

	private String getNothingFoundMessage(String message) {
		return "No Wikipedia page found for request: " + HTMLEncoder.encode(message, true, true, true) + "<br/><br/>"
			+ getSearchForm();
	}
	
	private String getSearchForm() {
		return "To search Wikipedia<br/>"
		+ "<form action='/wikipedia' method='get' class='" + CSS_TXTWEB_FORM + "' >"
		+ "search<input type='text' name='txtweb-message' />"
		+ "<input type='submit' value='Submit' />"
		+ "</form>";
	}

	private void sendResponse(HttpServletResponse httpResponse, String response) {
		try{
			httpResponse.setContentType("text/html; charset=UTF-8");
			PrintWriter out = httpResponse.getWriter();
			
			// Add all the surrounding HTML
			response = "<html><head><title>Wikipedia</title>"
				+ "<meta http-equiv='Content-Type' content='text/html; charset=UTF-8' />"
				+ "<meta name='" + APPKEY_NAME + "' content='" + APPKEY_CONTENT + "' />"
				+ "</head><body>" + response + "</body></html>";
			
			out.println(response);
		} catch (IOException e) {}		
	}

}
