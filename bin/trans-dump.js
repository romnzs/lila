const { readFile, writeFile } = require('fs/promises');
const { parseString } = require('xml2js');
const path = require('path');

const lilaDir = path.resolve(__dirname, '..');
const baseDir = path.resolve(lilaDir, 'translation/source');
const dbs =
  'site arena emails learn activity coordinates study clas contact patron coach broadcast streamer tfa settings preferences team perfStat search tourname faq lag swiss puzzle puzzleTheme challenge storm ublog insight keyboardMove'.split(
    ' '
  );

function ucfirst(s) {
  return s[0].toUpperCase() + s.slice(1);
}

function xmlName(name) {
  return name === 'clas' ? 'class' : name;
}

function keyListFrom(name) {
  return readFile(path.resolve(baseDir, `${xmlName(name)}.xml`), { encoding: 'utf8' }).then(txt => {
    return new Promise((resolve, reject) =>
      parseString(txt, (_, xml) => {
        const strings = (xml.resources.string || []).map(e => e['$'].name);
        const plurals = (xml.resources.plurals || []).map(e => e['$'].name);
        const keys = strings.concat(plurals);

        resolve({
          name,
          code:
            keys
              .map(k => `val \`${k}\` = new I18nKey("${name === 'site' ? '' : xmlName(name) + ':'}${k}")`)
              .join('\n') + '\n',
        });
      })
    );
  });
}

Promise.all(dbs.map(keyListFrom)).then(objs => {
  function dbCode(obj) {
    return obj.name === 'site' ? obj.code : `object ${obj.name} {\n${obj.code}\n}\n`;
  }

  const code = `// Generated with bin/trans-dump.js
package lila.i18n

// format: OFF
object I18nKeys {
${objs.map(dbCode).join('\n')}
}
`;

  return writeFile(path.resolve(lilaDir, 'modules/i18n/src/main/I18nKeys.scala'), code);
});
