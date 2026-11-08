package com.blueriver.brits.webrtc.service;
import com.fasterxml.jackson.databind.ObjectMapper;
/** args: host secret ttlSeconds identity. Prints the REAL TurnCredentialService output as JSON. */
public class TurnTool { public static void main(String[] a) throws Exception {
  TurnCredentialService s = new TurnCredentialService();
  s.host = a[0]; s.port = 3478; s.tlsPort = 5349; s.secret = a[1]; s.ttl = Long.parseLong(a[2]);
  System.out.println(new ObjectMapper().writeValueAsString(s.issue(a[3])));
} }
