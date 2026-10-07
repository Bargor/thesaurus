/** Rules fixtures must never address production, DEV, or Android integration namespaces. */
export function rulesProjectId(value = 'demo-thesaurus-rules') {
  if (typeof value !== 'string' || !/^demo-thesaurus-rules(?:-[a-z0-9]+(?:-[a-z0-9]+)*)?$/.test(value)) {
    throw new Error('Rules tests require demo-thesaurus-rules or an isolated demo-thesaurus-rules-* project.');
  }
  return value;
}
