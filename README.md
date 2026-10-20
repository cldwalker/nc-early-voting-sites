## Description

A fast, mobile-friendly way to find North Carolina early voting sites, as an alternative to https://vt.ncsbe.gov/EVSite/. It improves over the state site by making any county and site information linkable and easy to share. It also provides functionality to find the closes site to an address.

## Sharing with Others

* https://tagaholic.me/nc-early-voting-sites/?lang=es - Shows webpage in Spanish by default.
* https://tagaholic.me/nc-early-voting-sites/?county=durham - `county` param shows sites for a county e.g. durham. Webpage sets the correct url when in county view.
* https://tagaholic.me/nc-early-voting-sites/?county=durham&site=221-milton-rd - `site` params shows early voting site details. Webpage sets the correct url when in site view.

## Development

Requires [babashka](https://github.com/babashka/babashka) and Node.
Recommend installing node 26.0+ and npm 11.12.1+. Then:

```sh
npm install
npm run build      # or: npm run watch
bb serve           # http://localhost:8080
```

The webpage should render well on Chrome and Firefox on either desktop or mobile.

All of the JS is written with https://github.com/squint-cljs/squint. This is the way.

cljs code should lint with [clj-kondo](https://github.com/clj-kondo/clj-kondo) - `clj-kondo --lint src`

There are some dev tasks that use https://github.com/babashka/babashka. Run `bb tasks` to see what's
available.

## Contributing

I'm not looking for major contributions as this is mostly done.

## LLM Disclaimer

This webpage was largely built by LLMs. See [plan.md](plan.md) for the site's features and decisions.

## LICENSE

See LICENSE.md
