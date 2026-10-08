#!/usr/bin/env python3
"""Simple CGI script demonstrating GET query and POST body handling."""
import os
import sys
from urllib.parse import parse_qs

method = os.environ.get("REQUEST_METHOD", "GET")
query = os.environ.get("QUERY_STRING", "")
params = parse_qs(query)
name = params.get("name", ["stranger"])[0]

body = ""
if method == "POST":
    length = int(os.environ.get("CONTENT_LENGTH", "0") or "0")
    if length > 0:
        body = sys.stdin.read(length)

print("Content-Type: text/html; charset=utf-8")
print()
print("<!DOCTYPE html>")
print("<html><head><title>CGI</title></head><body>")
print(f"<h1>Hello, {name}!</h1>")
print(f"<p>Method: {method}</p>")
print(f"<p>PATH_INFO: {os.environ.get('PATH_INFO', '')}</p>")
if body:
    print(f"<p>POST body: {body}</p>")
print('<p><a href="/">Back home</a></p>')
print("</body></html>")
