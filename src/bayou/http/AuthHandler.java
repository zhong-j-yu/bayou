package bayou.http;

import _bayou._http._HttpUtil;
import _bayou._str._StrUtil;
import bayou.async.Async;
import bayou.mime.Headers;
import bayou.tcp.TcpAddress;
import bayou.util.UserPass;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import static _bayou._http._HttpUtil.toAsync;

/**

 http://tools.ietf.org/html/rfc7235
 http://tools.ietf.org/html/rfc2617
 http://en.wikipedia.org/wiki/Digest_access_authentication#Browser_implementation

 # challenge-proof

 if a request does not contain proper credentials, the server responds with a challenge.
 the client computes the proof of credentials for the challenge, and re-send the same request with the proof.

 the challenge is persistent. all following requests (that's "covered" by the challenge) should
 re-compute the proof based on the challenge.

 # domain of challenge

 we simply assume that a challenge covers all the requests to the same server (scheme+authority).
 this is likely true in practice. if not, study actual use cases and make changes. considerations:
 Digest - `domain` parameter in the challenge contains a set of uri path roots.
 if a request uri is under one of the roots, it's covered by the challenge.
 Basic  - we should assume that a realm covers 1 uri path root. we compute it as the common
 parent path of all request URIs that were met with challenge with the realm.

 # multiple challenges in a response

 we only do Basic and Digest. ignore others.
 if a response contains both Digest and Basic challenges, ignore Basic challenges.
 if a response contains multiple Basic  challenges, use only one of them
 if a response contains multiple Digest challenges, use only one of them
 therefore we only see one challenge in a response.

 # challenge history

 we may have a sequence of challenges from different responses.
 a request honors the latest challenge that covers it. so we only save the last challenge.

 # username, password

 for each challenge, we prompt for username password, which is cached with the challenge.
 if u/p doesn't work (on 1st request, or a later request), a new challenge will be issued,
 removing the cached u/p with its challenge.

 concurrent prompts for u/p for the same server+realm should be consolidated as one.

 # code

 first code it separately, don't mess it with client.send() and redirects
 (redirects may involve multiple auth on multiple servers)

 parse(response) -> challenge (Basic/Digest)
 challenge.setUserPass()
 challenge.compute(request) -> proof

 # send(req1)

 send req1, possibly with proof based on the prev challenge (with cached u/p)
 get_challenge(req), if not null, compute proof, set header
 if res1 is a challenge,
 invalidate prev_challenge (possibly get the new one from a concurrent party?)
 prompt for u/p
 compute new proof, re-send request as req2
 if res2 is not a challenge, the proof is accepted
 save the challenge with u/p as prev_challenge
 if res2 is still a challenge,
 fail (or configurable number of tries before fail; default 1? no. just once. user can "refresh" to retry)

 consider concurrency cases where a lot of requests are sent to the server. minimize prompts/computations.


 # server vs proxy
 do server only first. add proxy case later.


 */

// 403 response code :
// this code is only for server. there's no similar code for proxy. 403 is transparent to a proxy auth handler.
// if a server responds with 403, the access is denied. the request credentials may or may not be rejected.
// the questions are:
//     # should we un-cache the authInfo used by the request
//     # should we retry the request, if there's a new authInfo cached (by another process)
//       note that a 403 response usually carries no challenge.
// at this time, we only cater to the most common case - that the user indeed has no access to the resource,
// and he doesn't have an alternative credential to access it.
// therefore we won't un-cache the credential, won't retry the request.



// proxy/server authentication
// note: proxy auth is at lower layer than server auth

class AuthHandler implements HttpHandler
{
    TcpAddress proxyAddr;
    HttpHandler h0;

    Function<TcpAddress, Async<UserPass>> userPassFunc;
    // we may invoke this func concurrently for the same address.
    // but we don't want to present multiple prompts to end user.
    // for now, leave that problem to func itself, which can suppress concurrent prompts.

    // for multiple servers
    AuthHandler(HttpHandler h0, Function<TcpAddress, Async<UserPass>> userPassFunc)
    {
        this(null, h0, userPassFunc, new HashMap<>());
    }
    // for a single proxy
    AuthHandler(HttpHandler h0, TcpAddress proxyAddr, Supplier<Async<UserPass>> userPassSupplier)
    {
        this(proxyAddr, h0, addr->userPassSupplier.get(), new HashMap<>());
    }

    AuthHandler(TcpAddress proxyAddr, HttpHandler h0, Function<TcpAddress, Async<UserPass>> userPassFunc,
                HashMap<TcpAddress, AuthInfo> cache)
    {
        this.proxyAddr = proxyAddr;
        this.h0 = h0;
        this.userPassFunc = userPassFunc;
        this.cache = cache;
    }

    // ===========================================================================================================

    // including challenge from server, and user/pass from user. cached to provide proof for later requests.
    // initialized by parseChallenge(), then setUserPass()
    static abstract class AuthInfo
    {
        boolean cached;

        boolean isStale(){ return false; }

        // it's unclear what to do if username/password contains chars outside 0xFF.
        // however, whatever bytes that should be sent on the wire, it can always be
        // specified to us in userPass as ISO_8859_1 chars.
        UserPass userPass;
        void setUserPass(UserPass userPass)
        {
            this.userPass = userPass;
        }
        UserPass getUserPass()
        {
            return userPass;
        }

        abstract String calcProof(HttpRequest request, boolean proxy);
    }



    // the latest successful challenge+userPass for proxy/servers.
    // in the proxy case, there's only one entry, keyed by proxy address.
    final HashMap<TcpAddress, AuthInfo> cache;

    void cacheSave(TcpAddress address, AuthInfo authInfo)
    {
        synchronized (cache)
        {
            cache.put(address, authInfo);
        }
    }
    AuthInfo cacheGet(TcpAddress address)
    {
        synchronized (cache)
        {
            return cache.get(address);
        }
    }
    // invalidate a prev AuthInfo after it fails.
    void cacheInvalidate(TcpAddress address, AuthInfo authInfo)
    {
        synchronized (cache)
        {
            cache.remove(address, authInfo);
        }
    }




    // ===========================================================================================================

    @Override
    public Async<HttpResponse> handle(HttpRequest request)
    {
        TcpAddress address = proxyAddr;
        if(address==null)
            address = HttpClient.getDest(request); // req host
        if(address==null) // uh?
            return h0.handle(request); // should fail

        AuthInfo authInfoX = cacheGet(address); // could be null

        return send(request, address, authInfoX, /*retry*/1);
    }

    Async<HttpResponse> send(HttpRequest request, TcpAddress address, AuthInfo authInfoX, int retry)
    {
        if(authInfoX!=null)
        {
            String proof = authInfoX.calcProof(request, proxyAddr!=null);
            String HN =  proxyAddr!=null? Headers.Proxy_Authorization : Headers.Authorization;
            request = new HttpRequestImpl(request).header(HN, proof);
        }

        HttpRequest requestF = request;
        return h0.handle(requestF)
            .then(response -> onResponse(response, requestF, address, authInfoX, retry));
    }
    Async<HttpResponse> onResponse(HttpResponse response,
                                    HttpRequest request, TcpAddress address, AuthInfo authInfoX, int retry)
    {
        int challengeCode = proxyAddr!=null? 407 : 401;

        if(authInfoX!=null) // save or remove it
        {
            if(authInfoX.cached)
            {
                if(response.statusCode()==challengeCode) // authentication no longer valid
                {
                    cacheInvalidate(address, authInfoX);
                }
                // other 4xx/5xx codes are not enough to discredit it.
            }
            else
            {
                if(response.statusCode()!=challengeCode)
                {
                    authInfoX.cached=true;
                    cacheSave(address, authInfoX); // this is the latest successful one.
                }
                // if code is 4xx/5xx, the credential may or may not have worked.
                // consider: this is proxy auth, and response is 401 (from server)
                //           proxy auth obviously works, and should be cached
                // for now, we be optimistic, cache it, reasoning that it's not harmful anyway.
            }
        }

        if(response.statusCode()!=challengeCode)
            return toAsync(response);

        if(retry<=0)
            return toAsync(response);

        AuthInfo authInfoNew = parseChallenge(response);
        if(authInfoNew==null) // failed to parse
            return toAsync(response);

        // retry request with proof for new challenge.
        // remember to drain the current response

        // it's possible that another process just succeeded with authInfoX2 which is in cache,
        // and we could use it (if it's equivalent somehow) without prompt for userPass.
        // but: #1 concurrent case is probably not common.
        //      #2 authInfoX2 is likely a Digest with a different nonce.

        if(authInfoNew.isStale() && authInfoX!=null) // server indicates that userPass is ok
        {
            authInfoNew.setUserPass(authInfoX.getUserPass());
            return resend(response, request, address, authInfoNew, retry);
        }

        // prompt user/pass for this challenge
        return userPassFunc.apply(address).then(userPass ->
        {
            if (userPass == null) // we don't have user/pass for this server
                return toAsync(response);  // note: must not drain response before this step

            authInfoNew.setUserPass(userPass);
            return resend(response, request, address, authInfoNew, retry);
        });

    }
    Async<HttpResponse> resend(HttpResponse response,
                               HttpRequest request, TcpAddress address, AuthInfo authInfoX, int retry)
    {
        // drain response first before sending the new request. ok if draining fails.
        return HttpClient.drain(response)
            .then(r->send(request, address, authInfoX, retry-1));
    }




    // ========================================================================================================


    // char/byte, string/byte[], are the same concept here, as in C

    static byte[] c2b(String string)
    {
        return _StrUtil.latin1Bytes(string);
    }

    static class Basic extends AuthInfo
    {
        static Basic create(Map<String,String> params)
        {
            Basic basic = new Basic();
            basic.realm = params.get("realm");
            return basic;
        }

        String realm; // not used

        String proof; // constant

        @Override
        String calcProof(HttpRequest request, boolean proxy)
        {
            if(proof==null) // 1st request. not cached yet. no concurrency issue.
            {
                String s = userPass.username() + ":" + userPass.password();
                s = Base64.getEncoder().encodeToString( c2b(s) );
                proof = "Basic " + s;
            }

            return proof;
        }
    }

    static class Digest extends AuthInfo
    {
        static Digest create(Map<String,String> params)
        {
            Digest digest = new Digest();

            String v = params.get("qop");
            digest.qopAuth = ( v!=null && _HttpUtil.containsToken(v, "auth") );
            // we do not support "auth-int" in qop. if that is the only option in qop,
            // we are allowed to ignore "qop" all together. of course, the server may not like it.

            v = params.get("algorithm");
            if(v==null || v.equalsIgnoreCase("MD5"))
                digest.md5_sess=false;
            else if(v.equalsIgnoreCase("MD5-sess"))
                digest.md5_sess=true;
            else // unknown algorithm
                return null;

            if(digest.md5_sess && !digest.qopAuth)
                return null;  // rfc2617 implies that this is illegal

            v = params.get("realm");
            if(v==null) // required
                return null;
            digest.realm = c2b(v);

            v = params.get("nonce");
            if(v==null) // required
                return null;
            digest.nonce = c2b(v);

            v = params.get("opaque");
            if(v!=null)
                digest.opaque = c2b(v);

            digest.stale = "true".equalsIgnoreCase(params.get("stale"));

            return digest;
        }

        boolean qopAuth;
        boolean md5_sess;  // if true, qopAuth must be true too
        byte[] realm;
        byte[] nonce;
        byte[] opaque; // optional
        boolean stale;

        @Override
        boolean isStale()
        {
            return stale;
        }

        byte[] HA1; // constant
        AtomicLong nonceCount = new AtomicLong(0);

        @Override
        String calcProof(HttpRequest request, boolean proxy)
        {
            byte[] reqMethod = c2b( request.method() );
            byte[] reqUri = c2b( _HttpUtil.target(request, proxy) );

            if(!qopAuth)
                return calcProof(reqMethod, reqUri, null, null);

            byte[] cNonce = new byte[8];
            ThreadLocalRandom.current().nextBytes(cNonce);
            cNonce = loHex(cNonce);

            int nc = (int)nonceCount.incrementAndGet();
            byte[] ncV = new byte[8];
            for(int i=ncV.length-1; i>=0; i--)
            {
                ncV[i] = hex(nc);
                nc = nc>>4;
            }

            return calcProof(reqMethod, reqUri, cNonce, ncV);
        }
        String calcProof(byte[] reqMethod, byte[] reqUri, byte[] cNonce, byte[] ncV)
        {
            byte[] username = c2b(userPass.username());

            if(HA1==null) // 1st request. not cached yet, no concurrency issue.
            {
                HA1 = hash(username, realm, c2b(userPass.password()));
                if(md5_sess)
                    HA1 = hash(HA1, nonce, cNonce);
            }

            byte[] HA2 = hash(reqMethod, reqUri);

            byte[] reqDigest;
            if(qopAuth)
                reqDigest = hash(HA1, nonce, ncV, cNonce, c2b("auth"), HA2);
            else
                reqDigest = hash(HA1, nonce, HA2);

            StringBuilder sb = new StringBuilder();
            sb.append("Digest username="); quote(sb, username);
            sb.append(",realm="); quote(sb, realm);
            sb.append(",nonce="); quote(sb, nonce);
            sb.append(",uri="); quote(sb, reqUri);   // quoted. see errata
            sb.append(",response="); quote(sb, reqDigest);
            if(qopAuth)
            {
                sb.append(",qop=auth");
                sb.append(",cnonce="); quote(sb, cNonce);

                sb.append(",nc=");
                for(byte b:ncV) sb.append((char)(0xff & b));
            }
            if(opaque!=null)
                sb.append(",opaque="); quote(sb, opaque);
            if(md5_sess)  // otherwise "MD5" is implied
                sb.append(",algorithm=MD5-sess");

            return sb.toString();
        }
        // for testing with known c-nonce and nonce-count
        String calcProof(String reqMethod, String reqUri, String cNonce, String ncV)
        {
            return calcProof(c2b(reqMethod), c2b(reqUri),
                cNonce==null? null:c2b(cNonce),
                ncV==null? null : c2b(ncV));
        }


        static void quote(StringBuilder sb, byte[] chars)
        {
            sb.append('"');
            for(byte b : chars)
            {
                char c = (char)(0xff & b);
                if(c=='"' || c=='\\')
                    sb.append('\\');
                sb.append( c );
            }
            sb.append('"');
        }

        static final byte[] COLON = c2b(":");
        static byte[] hash(byte[]... list)
        {
            try
            {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(list[0]);
                for(int i=1; i<list.length; i++)
                {
                    md.update(COLON);
                    md.update(list[i]);
                }
                byte[] bytes = md.digest();

                return loHex( bytes ); // http://tools.ietf.org/html/rfc2617#section-3.1.3
            }
            catch (NoSuchAlgorithmException e)
            {
                throw new AssertionError(e);
            }
        }
        // bytes (00-ff) to hex chars 0-f
        static byte[] loHex(byte[] bytes)
        {
            byte[] hex = new byte[bytes.length*2];
            for(int i=0; i<bytes.length; i++)
            {
                byte b = bytes[i];
                hex[2*i  ] = hex(b>>4);
                hex[2*i+1] = hex(b);
            }
            return hex;
        }
        static byte hex(int x)  // lower 4 bit of x to hex char
        {
            x = x & 0xf;
            if(x<10)
                return (byte)(x+'0');
            else
                return (byte)(x-10+'a');
        }


    }


    // ========================================================================================================

    // parse challenge from server response. return null if fails.
    static AuthInfo parseChallenge(HttpResponse response)
    {
        String HN = response.statusCode()==401 ? Headers.WWW_Authenticate : Headers.Proxy_Authenticate;
        String string = response.headers().get(HN);
        if(string==null) return null;

        Basic basic = null;
        // find the 1st Digest; if none, find the 1st Basic.
        for(Challenge challenge : parseChallenges(string))
        {
            if(challenge.scheme.equals("digest"))
            {
                Digest digest = Digest.create(challenge.params); // may fail
                if(digest!=null)
                    return digest;
            }
            else if(challenge.scheme.equals("basic") && basic==null)
            {
                basic = Basic.create(challenge.params);
            }
        }
        return basic;
    }
    static class Challenge
    {
        String scheme;
        HashMap<String,String> params = new HashMap<>();
    }
    // we follow the grammar of rfc2617:  challenge = auth-scheme 1*SP 1#auth-param
    //   rfc7235 changed the grammar, breaking backward compatibility for no good reason.
    //   for now we don't think that is a concern (that a new/unknown scheme is introduced in the new grammar)
    // 1#challenge - think of it as a comma separated list of name=value,
    //   and if name contains 2 tokens, it's a start of a new scheme (e.g. "Digest realm")
    static List<Challenge> parseChallenges(String string)
    {
        ArrayList<Challenge> list = new ArrayList<>();
        Challenge challenge=null;
        int i=0, N = string.length();
        while(i<N)
        {
            // expect name=value

            int ix = string.indexOf('=', i);
            if(ix==-1) return list;   // malformed.
            String name = string.substring(i, ix).trim();
            name = _StrUtil.lowerCase(name);  // lower cased!

            String value;
            i = _StrUtil.skipWhiteSpaces(string, ix + 1);
            if(i<N && string.charAt(i)=='"') // quoted string. ended by closing quote
            {
                String[] result = {null};
                i = _StrUtil.parseQuotedString(string, i, N, result);
                // i is at the closing quote
                if(i==N) return list; // no closing quote.
                value = result[0];  // not trimmed! preserve spaces in quoted string
                i = _StrUtil.skipWhiteSpaces(string, i+1);
                if(i<N && string.charAt(i)!=',') // expect comma or EOF
                    return list;
            }
            else // token, ended by comma or EOF
            {
                ix = string.indexOf(',', i);
                if(ix==-1) ix=N;
                value = string.substring(i, ix).trim(); // trimmed!
                i = ix;
            }
            // i is at comma or EOF
            i++;

            // ...................................

            ix = name.indexOf(' ');
            if(ix!=-1) // e.g. "Digest realm". start of a new scheme
            {
                list.add(challenge = new Challenge());
                challenge.scheme = name.substring(0, ix);
                name = name.substring(ix+1).trim();
            }

            if(challenge==null) return list;
            challenge.params.put(name, value);
        }

        return list;
    }


}
