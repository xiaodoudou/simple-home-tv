# Security

## Reporting a vulnerability

Please don't open a public issue. Use [Report a vulnerability](https://github.com/xiaodoudou/simple-home-tv/security/advisories/new)
on GitHub, which keeps it private until there is a fix. Say what you found, how to reproduce it, and what it lets
someone do.

## What is worth knowing

- **Network.** HTTPS for the 4K aerial videos. Some TVs lack Apple's root certificate, so the app
  ships it and trusts it for Apple's video host only. The Full HD aerial streams use plain HTTP, like Apple's own
  catalog links.
