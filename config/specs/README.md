# Platform specs

One YAML file per class A platform (public JSON or XML). The crawler's spec engine
(`crawler/.../spec/SpecAdapter.java`) runs any platform described here, so adding a class A
platform is a spec file plus a tenant list, with no Java code. The file name must be `<name>.yaml`.

A pipeline points a platform at its spec:

```yaml
platforms:
  - name: lever
    spec: /etc/jbi/specs/lever.yaml        # the image ships this directory
    tenants_file: /etc/jbi/tenants/lever.csv
    base_url: "http://fixture:8080"        # optional: overrides the spec's base_url
```

The crawler loads every spec at start-up and refuses to start when one is invalid, listing every
problem it found.

## Keys

| Key | Required | Meaning |
| --- | --- | --- |
| `name` | yes | Platform name; equals the file name |
| `access_class` | no | `A` (default). B and C are not run by the spec engine |
| `format` | yes | `json` or `xml` |
| `base_url` | when `list.url` uses it | Default host; the pipeline's `base_url` overrides it |
| `namespaces` | xml only | Prefix to URI, for XPath (`g: http://base.google.com/ns/1.0`) |
| `list.url` | yes | URL template. Variables: `{base_url}`, `{board}` (URL-encoded), `{company}` |
| `list.items` | yes | Path to the job list. JSON: `jobs`, or `$` when the body is the array. XML: an XPath such as `/rss/channel/item` |
| `list.marker` | yes | Paths that must exist on every page. A page without them is a **failed fetch, never zero jobs** |
| `list.total` | no | Path to the platform's job count. The snapshot is complete only when the parsed count equals it |
| `pagination` | no | See below; default `style: none` |
| `rate_limit` | no | Carried for the politeness layer (T2.4), for example `requests_per_second: 1` |
| `fields` | `title`, `url` | Job record field to source; see below |

## Pagination

| `style` | Keys | Stops when |
| --- | --- | --- |
| `none` | | after one request |
| `offset` | `param` (offset name), optional `size_param` + `page_size`, `start` (default 0) | a page is empty or shorter than `page_size`, or `total` is reached |
| `page` | `param` (page number), optional `size_param` + `page_size`, `start` (default 1) | same as offset |
| `cursor` | `param` (query name), `next` (path to the cursor in the response) | no cursor or an empty page |
| `next_url` | `next` (path to the next URL; relative URLs are resolved) | no next URL or an empty page |

`max_pages` (default 500) is a safety stop. A board that reaches it is an **incomplete** snapshot,
never a complete one. A job repeated on two pages (the list shifted while being read) is kept once.

## Fields

Fields: `platform_job_id`, `title`, `company`, `description`, `location`, `city`, `region`,
`country`, `requisition_id`, `employment_type`, `remote` (boolean), `posted_at` (datetime), `url`,
`application_url`.

A bare string is a path. The long form takes exactly one source:

```yaml
title: text                                        # path
location: { path: [location.name, office] }        # first path with a value
description: { concat: [description, lists.*.content], transform: [html_to_text] }
url: { template: "{base_url}/job/{id}" }           # request variables, then item paths
remote: { path: workplaceType, type: boolean, true_values: [remote] }
posted_at: { path: createdAt, type: datetime, format: epoch_millis }
```

- **JSON paths** are dot-separated: `meta.total`, `categories.location`, `lists.*.content`
  (`*` or `[*]` fans out over an array), `offices.0.name`.
- **XML paths** are XPath 1.0 relative to the item: `title`, `g:id`, `pubDate[last()]`.
- **Transforms**, applied in order: `html_unescape`, `html_to_text`, `trim`, `normalize_list`
  (`,Portland,OR,97224` becomes `Portland, OR, 97224`).
- **Datetime formats**: `auto` (default), `iso`, `epoch_millis`, `epoch_seconds`, `rfc1123`.
  US zone abbreviations (EST, PDT, ...) are read as fixed offsets. A date that does not parse is
  dropped, never guessed.
- **Company:** the tenant list's company name wins when it has one.
- **platform_job_id** falls back to `url` when the spec has no id.

## Adding a platform

1. Save a real response under `crawler/src/test/resources/fixtures/`, trimmed.
2. Write `<name>.yaml` here and check robots.txt for the list path; note the result in the file.
3. Add a test to `ShippedSpecsTest` that runs the spec against the fixture, including a response
   without the board marker.
4. Add `local/tenants/<name>.csv` and the platform to `local/pipelines.tmpl.yaml`.

Schema validation in CI and ConfigMap packaging come with T2.7 and T2.9.
