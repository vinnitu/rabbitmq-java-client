// Copyright (c) 2007-Present Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.tools.jsonrpc;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.RpcClient;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.tools.json.JSONReader;
import com.rabbitmq.tools.json.JSONWriter;

/**
	  <a href="http://json-rpc.org">JSON-RPC</a> is a lightweight
	  RPC mechanism using <a href="http://www.json.org/">JSON</a>
	  as a data language for request and reply messages. It is
	  rapidly becoming a standard in web development, where it is
	  used to make RPC requests over HTTP. RabbitMQ provides an
	  AMQP transport binding for JSON-RPC in the form of the
	  <code>JsonRpcClient</code> class.

	  JSON-RPC services are self-describing - each service is able
	  to list its supported procedures, and each procedure
	  describes its parameters and types. An instance of
	  JsonRpcClient retrieves its service description using the
	  standard <code>system.describe</code> procedure when it is
	  constructed, and uses the information to coerce parameter
	  types appropriately. A JSON service description is parsed
	  into instances of <code>ServiceDescription</code>. Client
	  code can access the service description by reading the
	  <code>serviceDescription</code> field of
	  <code>JsonRpcClient</code> instances.

	  @see #call(String, Object[])
	  @see #call(String[])
 */
public class JsonRpcClient extends RpcClient implements InvocationHandler {
    /** Holds the JSON-RPC service description for this client. */
    private ServiceDescription serviceDescription;

    public static boolean full = true;

    /**
     * Construct a new JsonRpcClient, passing the parameters through
     * to RpcClient's constructor. The service description record is
     * retrieved from the server during construction.
     * @throws TimeoutException if a response is not received within the timeout specified, if any
     */
    public JsonRpcClient(Channel channel, String exchange, String routingKey, boolean check, int timeout)
        throws IOException, JsonRpcException, TimeoutException
    {
	super(channel, exchange, routingKey, timeout);
        if (check) {
            retrieveServiceDescription();
        }
    }

    public JsonRpcClient(Channel channel, String exchange, String routingKey, boolean check)
    throws IOException, JsonRpcException, TimeoutException
    {
        this(channel, exchange, routingKey, check, RpcClient.NO_TIMEOUT);
    }

    public JsonRpcClient(Channel channel, String exchange, String routingKey)
    throws IOException, JsonRpcException, TimeoutException
    {
        this(channel, exchange, routingKey, true, RpcClient.NO_TIMEOUT);
    }

    /**
     * Private API - parses a JSON-RPC reply object, checking it for exceptions.
     * @return the result contained within the reply, if no exception is found
     * Throws JsonRpcException if the reply object contained an exception
     */
    public static Object checkReply(Map<String, Object> reply)
        throws JsonRpcException
    {
	if (reply.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) reply.get("error");
            // actually a Map<String, Object>
            throw new JsonRpcException(map);
        }

        Object result = reply.get("result");
        //System.out.println(new JSONWriter().write(result));
        return result;
    }

    /**
     * Public API - builds, encodes and sends a JSON-RPC request, and
     * waits for the response.
     * @return the result contained within the reply, if no exception is found
     * @throws JsonRpcException if the reply object contained an exception
     * @throws TimeoutException if a response is not received within the timeout specified, if any
     */
    public Object call(String method, Object params) throws IOException, JsonRpcException, TimeoutException
    {
        HashMap<String, Object> request = new HashMap<String, Object>();
        request.put("id", null);
        request.put("method", method);
        if (full) {
            request.put("jsonrpc", ServiceDescription.JSON_RPC_VERSION);
        }
        request.put("params", (params == null) ? new Object[0] : params);
        String requestStr = new JSONWriter().write(request);
        try {
            String replyStr = this.stringCall(requestStr);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) (new JSONReader().read(replyStr));
            return checkReply(map);
        } catch(ShutdownSignalException ex) {
            throw new IOException(ex.getMessage()); // wrap, re-throw
        }

    }

    public void void_call(String method, Object params) throws IOException, JsonRpcException, TimeoutException
    {
        HashMap<String, Object> request = new HashMap<String, Object>();
        request.put("method", method);
        if (full) {
            request.put("jsonrpc", ServiceDescription.JSON_RPC_VERSION);
        }
        request.put("params", (params == null) ? new Object[0] : params);
        String requestStr = new JSONWriter().write(request);
        try {
            this.publish(null, requestStr.getBytes());
        } catch(ShutdownSignalException ex) {
            throw new IOException(ex.getMessage()); // wrap, re-throw
        }
    }

    /**
     * Public API - implements InvocationHandler.invoke. This is
     * useful for constructing dynamic proxies for JSON-RPC
     * interfaces.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable
    {
        if (method.getReturnType().equals(Void.TYPE)) {
             void_call(method.getName(), args);
             return null;
        } else {
             return call(method.getName(), args);
        }
    }

    /**
     * Public API - gets a dynamic proxy for a particular interface class.
     */
    public Object createProxy(Class<?> klass)
        throws IllegalArgumentException
    {
        return Proxy.newProxyInstance(klass.getClassLoader(),
                                      new Class[] { klass },
                                      this);
    }

    /**
     * Private API - used by {@link #call(String[])} to ad-hoc convert
     * strings into the required data types for a call.
     */
    public static Object coerce(String val, String type)
	throws NumberFormatException
    {
	if ("bit".equals(type)) {
	    return Boolean.getBoolean(val) ? Boolean.TRUE : Boolean.FALSE;
	} else if ("num".equals(type)) {
	    try {
		return new Integer(val);
	    } catch (NumberFormatException nfe) {
		return new Double(val);
	    }
	} else if ("str".equals(type)) {
	    return val;
	} else if ("arr".equals(type) || "obj".equals(type) || "any".equals(type)) {
	    return new JSONReader().read(val);
	} else if ("nil".equals(type)) {
	    return null;
	} else {
	    throw new IllegalArgumentException("Bad type: " + type);
	}
    }

    /**
     * Public API - as {@link #call(String,Object[])}, but takes the
     * method name from the first entry in <code>args</code>, and the
     * parameters from subsequent entries. All parameter values are
     * passed through coerce() to attempt to make them the types the
     * server is expecting.
     * @return the result contained within the reply, if no exception is found
     * @throws JsonRpcException if the reply object contained an exception
     * @throws NumberFormatException if a coercion failed
     * @throws TimeoutException if a response is not received within the timeout specified, if any
     * @see #coerce
     */
    public Object call(String[] args)
	throws NumberFormatException, IOException, JsonRpcException, TimeoutException
    {
	if (args.length == 0) {
	    throw new IllegalArgumentException("First string argument must be method name");
	}

	String method = args[0];
        int arity = args.length - 1;
	ProcedureDescription proc = serviceDescription.getProcedure(method, arity);
	ParameterDescription[] params = proc.getParams();

	Object[] actuals = new Object[arity];
	for (int count = 0; count < params.length; count++) {
	    actuals[count] = coerce(args[count + 1], params[count].type);
	}

	return call(method, actuals);
    }

    /**
     * Public API - gets the service description record that this
     * service loaded from the server itself at construction time.
     */
    public ServiceDescription getServiceDescription() {
	return serviceDescription;
    }

    /**
     * Private API - invokes the "system.describe" method on the
     * server, and parses and stores the resulting service description
     * in this object.
     * TODO: Avoid calling this from the constructor.
     * @throws TimeoutException if a response is not received within the timeout specified, if any
     */
    private void retrieveServiceDescription() throws IOException, JsonRpcException, TimeoutException
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> rawServiceDescription = (Map<String, Object>) call("system.describe", null);
        serviceDescription = new ServiceDescription(rawServiceDescription);
    }
}
