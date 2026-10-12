# CSV entries format

Use the [editable UTF-8 template](templates/thesaurus-entries.csv) as a starting point. It contains synthetic examples, including a blank title, Unicode, quotes, and a title spanning two physical lines. Replace its rows with your own entries. In your selected household, create or select the matching active categories and subcategories before adding rows. The examples use `Food`, `Salary`, and `Food / Groceries`.

CSV transfers ledger entries. Use JSON backup when you need opening balance, taxonomy definitions, IDs, audit fields, archive flags, or deleted entries. CSV export omits deleted entries and retains historical category/subcategory names, including archived taxonomy; importing a row requires active taxonomy.

## File and columns

The first record is the header. Encode the file as UTF-8; a leading UTF-8 BOM is accepted. Comma, semicolon, and tab delimiters are supported. Detection uses the header. If detection is ambiguous in the app, reformat the source with a consistent delimiter and the English header shown below, then select the corrected file again. The parser API also accepts an explicit delimiter; the app has no delimiter chooser. Each data record must have the same number of cells as the header. Quote cells containing delimiters, quotes, or newlines with double quotes, and escape a literal double quote by doubling it. Do not add spaces outside a closing quote.

Export always uses the English header below, comma delimiters, UTF-8 with BOM, and CRLF record endings. Entries are ordered by date, then ID. Dates, decimal amounts, and type labels are independent of the device language.

```csv
date,amount,type,title,category,subcategory,tags,author
```

Only `date`, `amount`, and `category` must be mapped. Header matching trims surrounding whitespace and ignores case using a language-neutral convention. Duplicate recognized headers are ambiguous, including optional headers; resolve them in column mapping. Unknown columns may remain unmapped. These are the built-in aliases:

| Column | Supported headers |
| --- | --- |
| date | date, data, datum |
| amount | amount, kwota, betrag, montant |
| type | type, typ, art |
| title | title, tytuł, tytul, titel, titre |
| category | category, kategoria, kategorie, catégorie, categorie |
| subcategory | subcategory, podkategoria, unterkategorie, sous-catégorie, sous-categorie |
| tags | tags, tagi, schlagwörter, schlagworter, étiquettes, etiquettes |
| author | author, autor, auteur |

The `author` column is recognized but ignored for importing identities. Every imported entry belongs to the selected household and is authored and updated by the signed-in owner.

## Values

`date`: Prefer `YYYY-MM-DD`, for example `2026-01-10`. Regional dates use one or two digits for each day/month and four digits for the year, separated consistently by dots or slashes. Automatic mode interprets dotted dates as day/month/year. Slash dates are accepted automatically only when one interpretation is a valid calendar date, or both interpretations give the same date. For `01/02/2026`, choose day/month/year or month/day/year explicitly. An explicit regional convention applies to both dots and slashes; ISO dates remain ISO. Future dates and invalid calendar dates cannot be added.

`amount`: A nonzero signed decimal in PLN, with at most two decimal places and ASCII digits, for example `-12.34`, `+2500`, or `12,34`. No thousands separators, currency symbols, spaces inside the number, exponent notation, or rounding. When comma is the delimiter, quote a decimal-comma amount. Values are stored exactly as signed 64-bit integer grosze; the allowed endpoints are `-92233720368547758.08` and `92233720368547758.07`.

`type`: Optional. Income labels are `income`, `przychód`, `przychod`, `wpływ`, `wplyw`, `einnahme`, `revenu`; expense labels are `expense`, `wydatek`, `koszt`, `ausgabe`, `dépense`, `depense`. Matching trims and ignores case. For an unsigned amount, expense makes it negative; income or a blank type makes it positive. An explicitly signed amount must agree with a nonblank type: `+12.34,expense` and `-12.34,income` are invalid. This file type determines the entry direction independently of the category's default entry type. Unknown labels are invalid. Validator integrations may supply additional aliases; built-in type meanings take precedence over those aliases.

`title`: Optional, up to 160 UTF-16 code units. Blank titles are allowed. Import trims the title and stores an empty result as absent; export preserves the stored text. Embedded newlines are allowed in a quoted cell.

`category` and `subcategory`: Category is required, up to 60 UTF-16 code units. Subcategory is optional, up to 60 units; an empty cell means none, while a whitespace-only nonempty cell is invalid. Names match after trimming and case folding. A subcategory is resolved within its category. Missing, ambiguous, or archived matches require attention before the row can be added. You can select active taxonomy or create new taxonomy in the import flow.

`tags`: Blank or a strict JSON array of strings, such as `["food,home","żółć"]`. In a comma-delimited file that cell is `"[""food,home"",""żółć""]"`. Maximum 10 tags, each nonblank and at most 40 UTF-16 code units. Invalid JSON, non-string elements, and identical duplicate tags are rejected. JSON escapes preserve quotes and newlines. Import trims tags, lowercases them using a language-neutral convention, removes duplicates after normalization, and sorts them. Export sorts stored tags and writes a JSON array (`[]` for no tags).

## Import outcomes and limits

Import requires the owner of the selected household, the same signed-in session, and a synchronized server snapshot. Preview is read-only. Valid rows can be added while invalid rows remain available for correction or discard; an invalid row does not write an entry. Source values remain available for review. Duplicate detection compares date, signed amount, normalized title, resolved category/subcategory IDs, and normalized tags, ignoring author and source IDs. Reimporting an unchanged entry skips it; duplicates within a file are also skipped. Existing history, including deleted entries, participates in duplicate detection.

The file limit is 16 MiB, with at most 50,000 data records, 128 columns, and 16,384 UTF-16 code units per raw cell. Malformed UTF-8, malformed quoting, and transport-limit failures reject parsing before any row import. Reported row numbers refer to physical start lines, so a quoted multiline cell changes later row numbers.

If opening CSV in a spreadsheet, import text columns as text. CSV quoting preserves text, including leading formula characters; it does not prevent spreadsheet formula execution.
