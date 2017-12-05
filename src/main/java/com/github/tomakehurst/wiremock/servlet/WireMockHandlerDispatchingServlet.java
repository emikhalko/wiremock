/*
 * Copyright (C) 2011 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock.servlet;

import com.github.tomakehurst.wiremock.common.LocalNotifier;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.core.FaultInjector;
import com.github.tomakehurst.wiremock.core.WireMockApp;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import javax.servlet.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;
import static com.github.tomakehurst.wiremock.http.RequestMethod.GET;
import static com.github.tomakehurst.wiremock.servlet.WireMockHttpServletRequestAdapter.ORIGINAL_REQUEST_KEY;
import static com.google.common.base.Charsets.UTF_8;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.URLDecoder.decode;
import static java.util.concurrent.Executors.newScheduledThreadPool;

public class WireMockHandlerDispatchingServlet extends HttpServlet {

    public static final String SHOULD_FORWARD_TO_FILES_CONTEXT = "shouldForwardToFilesContext";
    public static final String ASYNCHRONOUS_RESPONSE_ENABLED = "asynchronousResponseEnabled";
    public static final String ASYNCHRONOUS_RESPONSE_THREADS = "asynchronousResponseThreads";
    public static final String MAPPED_UNDER_KEY = "mappedUnder";

	private static final long serialVersionUID = -6602042274260495538L;

    private ScheduledExecutorService scheduledExecutorService;

    private RequestHandler requestHandler;
    private FaultInjectorFactory faultHandlerFactory;
	private String mappedUnder;
	private Notifier notifier;
	private String wiremockFileSourceRoot = "/";
	private boolean shouldForwardToFilesContext;
    private boolean asynchronousResponseEnabled;

	@Override
	public void init(ServletConfig config) {
	    ServletContext context = config.getServletContext();
	    shouldForwardToFilesContext = getFileContextForwardingFlagFrom(config);

	    if (context.getInitParameter("WireMockFileSourceRoot") != null) {
	        wiremockFileSourceRoot = context.getInitParameter("WireMockFileSourceRoot");
	    }

        asynchronousResponseEnabled = Boolean.valueOf(config.getInitParameter(ASYNCHRONOUS_RESPONSE_ENABLED));

        if (asynchronousResponseEnabled) {
            scheduledExecutorService = newScheduledThreadPool(Integer.valueOf(config.getInitParameter(ASYNCHRONOUS_RESPONSE_THREADS)));
        }

        String handlerClassName = config.getInitParameter(RequestHandler.HANDLER_CLASS_KEY);
		String faultInjectorFactoryClassName = config.getInitParameter(FaultInjectorFactory.INJECTOR_CLASS_KEY);
		mappedUnder = getNormalizedMappedUnder(config);
		context.log(RequestHandler.HANDLER_CLASS_KEY + " from context returned " + handlerClassName +
			". Normalized mapped under returned '" + mappedUnder + "'");
        requestHandler = (RequestHandler) context.getAttribute(handlerClassName);

        faultHandlerFactory = faultInjectorFactoryClassName != null ?
            (FaultInjectorFactory) context.getAttribute(faultInjectorFactoryClassName) :
            new NoFaultInjectorFactory();

		notifier = (Notifier) context.getAttribute(Notifier.KEY);
	}

	/**
	 * @param config Servlet configuration to read
	 * @return Normalized mappedUnder attribute without trailing slash
	*/
	private String getNormalizedMappedUnder(ServletConfig config) {
		String mappedUnder = config.getInitParameter(MAPPED_UNDER_KEY);
		if(mappedUnder == null) {
			return null;
		}
		if (mappedUnder.endsWith("/")) {
			mappedUnder = mappedUnder.substring(0, mappedUnder.length() - 1);
		}
		return mappedUnder;
	}

	private boolean getFileContextForwardingFlagFrom(ServletConfig config) {
		String flagValue = config.getInitParameter(SHOULD_FORWARD_TO_FILES_CONTEXT);
		return Boolean.valueOf(flagValue);
	}

	@Override
	protected void service(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
		LocalNotifier.set(notifier);

		Request request = new WireMockHttpServletRequestAdapter(httpServletRequest, mappedUnder);

		ServletHttpResponder responder = new ServletHttpResponder(httpServletRequest, httpServletResponse);
		requestHandler.handle(request, responder);
	}

	private class ServletHttpResponder implements HttpResponder {

		private final HttpServletRequest httpServletRequest;
		private final HttpServletResponse httpServletResponse;

		public ServletHttpResponder(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
			this.httpServletRequest = httpServletRequest;
			this.httpServletResponse = httpServletResponse;
		}

		@Override
		public void respond(final Request request, final Response response) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}

			httpServletRequest.setAttribute(ORIGINAL_REQUEST_KEY, LoggedRequest.createFrom(request));

            if (isAsyncSupported(response, httpServletRequest)) {
                respondAsync(request, response);
            } else {
                respondSync(request, response);
            }
        }

        private void respondSync(Request request, Response response) {
            delayIfRequired(response.getInitialDelay());
            respondTo(request, response);
        }


        private void delayIfRequired(long delayMillis) {
            try {
                TimeUnit.MILLISECONDS.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean isAsyncSupported(Response response, HttpServletRequest httpServletRequest) {
            return asynchronousResponseEnabled && response.getInitialDelay() > 0 && httpServletRequest.isAsyncSupported();
        }

        private void respondAsync(final Request request, final Response response) {
            final AsyncContext asyncContext = httpServletRequest.startAsync();
            scheduledExecutorService.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        respondTo(request, response);
                    } finally {
                        asyncContext.complete();
                    }
                }
            }, response.getInitialDelay(), TimeUnit.MILLISECONDS);
        }

        private void respondTo(Request request, Response response) {
            try {
                if (response.wasConfigured()) {
                    applyResponse(response, httpServletRequest, httpServletResponse);
                } else if (request.getMethod().equals(GET) && shouldForwardToFilesContext) {
                    forwardToFilesContext(httpServletRequest, httpServletResponse, request);
                } else {
                    httpServletResponse.sendError(HTTP_NOT_FOUND);
                }
            } catch (Exception e) {
                throwUnchecked(e);
            }
        }
    }

    public void applyResponse(Response response, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        Fault fault = response.getFault();
        if (fault != null) {
			FaultInjector faultInjector = buildFaultInjector(httpServletRequest, httpServletResponse);
			fault.apply(faultInjector);
            httpServletResponse.addHeader(Fault.class.getName(), fault.name());
            return;
        }

		if (response.getStatusMessage() == null) {
			httpServletResponse.setStatus(response.getStatus());
		} else {
			httpServletResponse.setStatus(response.getStatus(), response.getStatusMessage());
		}

        for (HttpHeader header: response.getHeaders().all()) {
            for (String value: header.values()) {
                httpServletResponse.addHeader(header.key(), value);
            }
        }

        if (response.shouldAddChunkedDribbleDelay()) {
			writeAndTranslateExceptionsWithChunkedDribbleDelay(httpServletResponse, response.getBody(), response.getChunkedDribbleDelay());
		} else {
			writeAndTranslateExceptions(httpServletResponse, response.getBody());
		}
    }

	private FaultInjector buildFaultInjector(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
	    return faultHandlerFactory.buildFaultInjector(httpServletRequest, httpServletResponse);
	}

    private static void writeAndTranslateExceptions(HttpServletResponse httpServletResponse, byte[] content) {
        try {
            ServletOutputStream out = httpServletResponse.getOutputStream();
            out.write(content);
            out.flush();
            out.close();
        } catch (IOException e) {
            throwUnchecked(e);
        }
    }

	private void writeAndTranslateExceptionsWithChunkedDribbleDelay(HttpServletResponse httpServletResponse, byte[] body, ChunkedDribbleDelay chunkedDribbleDelay) {

		try (ServletOutputStream out = httpServletResponse.getOutputStream()) {

			if (body.length < 1) {
				notifier.error("Cannot chunk dribble delay when no body set");
				out.flush();
				return;
			}

			byte[][] chunkedBody = BodyChunker.chunkBody(body, chunkedDribbleDelay.getNumberOfChunks());

			int chunkInterval = chunkedDribbleDelay.getTotalDuration() / chunkedBody.length;

			for (byte[] bodyChunk : chunkedBody) {
				Thread.sleep(chunkInterval);
				out.write(bodyChunk);
				out.flush();
			}

		} catch (IOException e) {
			throwUnchecked(e);
		} catch (InterruptedException ignored) {
		    // Ignore the interrupt quietly since it's probably the client timing out, which is a completely valid outcome
        }
	}

    private void forwardToFilesContext(HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse, Request request) throws ServletException, IOException {
        String forwardUrl = wiremockFileSourceRoot + WireMockApp.FILES_ROOT + request.getUrl();
        RequestDispatcher dispatcher = httpServletRequest.getRequestDispatcher(decode(forwardUrl, UTF_8.name()));
        dispatcher.forward(httpServletRequest, httpServletResponse);
    }
}
