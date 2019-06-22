/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.codeu.servlets;

import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.codeu.data.Datastore;
import com.google.codeu.data.Message;
import com.google.gson.Gson;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.LanguageServiceClient;
import com.google.cloud.language.v1.Sentiment;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document.OutputSettings;

/** Handles fetching and saving {@link Message} instances. */
@WebServlet("/messages")
public class MessageServlet extends HttpServlet {

  private Datastore datastore;

  @Override
  public void init() {
    datastore = new Datastore();
  }

  /**
   * Responds with a JSON representation of {@link Message} data for a specific user. Responds with
   * an empty array if the user is not provided.
   */
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

    response.setContentType("application/json");

    String user = request.getParameter("user");

    if (user == null || user.equals("")) {
      // Request is invalid, return empty array
      response.getWriter().println("[]");
      return;
    }

    List<Message> messages = datastore.getMessages(user);
    Gson gson = new Gson();
    String json = gson.toJson(messages);

    response.getWriter().println(json);
  }

  /** Stores a new {@link Message}. */
  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

    UserService userService = UserServiceFactory.getUserService();
    if (!userService.isUserLoggedIn()) {
      response.sendRedirect("/index.html");
      return;
    }

    String user = userService.getCurrentUser().getEmail();
    // Allow new line characters
    String userText = Jsoup.clean(request.getParameter("text"), "", Whitelist.none(), new OutputSettings().prettyPrint(false));

    String mediaRegex = "\\s(https?://\\S+\\.(png|jpg|bmp|gif|svg|mp3|mp4))(\\s|$|\\n)";
    String transformedText = displayMedia(mediaRegex, userText);

    LanguageServiceClient languageService = LanguageServiceClient.create();
    Document doc = Document.newBuilder().setContent(userText).setType(Document.Type.PLAIN_TEXT).build();
    Sentiment sentiment = languageService.analyzeSentiment(doc).getDocumentSentiment();
    double score = sentiment.getScore();
    languageService.close();
    Message message = new Message(user, transformedText, score);
    datastore.storeMessage(message);

    response.sendRedirect("/user-page.html?user=" + user);
  }

  public boolean isValidURL(String url) {
    try {
      new URL(url).toURI();
      return true;
    } catch (Exception e) {
      System.out.println("Invalid image URL provided");
      return false;
    }
  }

  public String displayMedia(String regexURL, String userInput) {
    String replacement;
    String textWithMediaReplaced = userInput;

    Pattern pattern = Pattern.compile(regexURL);
    Matcher matcher = pattern.matcher(userInput);

    List<String> imageExtensions = Arrays.asList(".png", ".jpg", ".bmp", ".gif", ".svg");

    while (matcher.find()) {
      String mediaURL = matcher.group(0);
      System.out.println("A media url found: " + mediaURL);

      if (isValidURL(mediaURL)) {
        if (mediaURL.endsWith(".mp3")) {
          replacement = "<audio controls src=" + mediaURL + " />";
          textWithMediaReplaced = textWithMediaReplaced.replace(mediaURL, replacement);
          System.out.println("URL changed with audio tag: " + textWithMediaReplaced);
        } else if (mediaURL.endsWith(".mp4")) {
          replacement = "<video controls src=" + mediaURL + " />";
          textWithMediaReplaced = textWithMediaReplaced.replace(mediaURL, replacement);
          System.out.println("URL changed with video tag: " + textWithMediaReplaced);
        } else if (imageExtensions.stream().anyMatch(ext -> mediaURL.endsWith(ext))) {
          replacement = "<img src=" + mediaURL + " />";
          textWithMediaReplaced = textWithMediaReplaced.replace(mediaURL, replacement);
          System.out.println("URL changed with image tag: " + textWithMediaReplaced);
        }
      } else {
        replacement = mediaURL + " (Not a valid URL)";
        textWithMediaReplaced = textWithMediaReplaced.replace(mediaURL, replacement);
        System.out.println("Invalid URL note: " + textWithMediaReplaced);
      }
    }

    return textWithMediaReplaced;
  }
}

