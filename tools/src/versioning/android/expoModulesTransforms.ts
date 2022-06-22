import escapeRegExp from 'lodash/escapeRegExp';

import { FileTransforms } from '../../Transforms.types';
import { packagesToKeep, packagesToRename } from './packagesConfig';
import { deleteLinesBetweenTags } from './utils';

function expoModulesBaseTransforms(abiVersion: string): FileTransforms {
  return {
    path: [
      {
        find: /src\/main\/java/,
        replaceWith: `src/main/java/${abiVersion}`,
      },
      {
        find: /src\/main\/kotlin/,
        replaceWith: `src/main/java/${abiVersion}`,
      },
      {
        find: /src\/main\/AndroidManifest\.xml/,
        replaceWith: 'src/main/TemporaryExpoModuleAndroidManifest.xml',
      },
    ],
    content: [
      ...packagesToKeep.map((pkg: string) => ({
        paths: ['./src/main/{java,kotlin}/**/*.{java,kt}', './src/main/AndroidManifest.xml'],
        find: new RegExp(`([, ^(<])${escapeRegExp(pkg)}`, 'g'),
        replaceWith: `$1temporarydonotversion.${pkg}`,
      })),
      ...packagesToRename.map((pkg: string) => ({
        paths: ['./src/main/{java,kotlin}/**/*.{java,kt}', './src/main/AndroidManifest.xml'],
        find: new RegExp(`([, ^(<])${escapeRegExp(pkg)}`, 'g'),
        replaceWith: `$1${abiVersion}.${pkg}`,
      })),
      {
        paths: ['./src/main/{java,kotlin}/**/*.{java,kt}', './src/main/AndroidManifest.xml'],
        find: /temporarydonotversion\./g,
        replaceWith: '',
      },
      {
        paths: './src/main/{java,kotlin}/**/*.java',
        find: /\/\/ *EXPO_VERSIONING_NEEDS_EXPOVIEW_R/g,
        replaceWith: `import ${abiVersion}.host.exp.expoview.R;`,
      },
      {
        paths: './src/main/{java,kotlin}/**/*.kt',
        find: /\/\/ *EXPO_VERSIONING_NEEDS_EXPOVIEW_R/g,
        replaceWith: `import ${abiVersion}.host.exp.expoview.R`,
      },
    ],
  };
}

export function expoModulesTransforms(module: string, abiVersion: string): FileTransforms {
  const base = expoModulesBaseTransforms(abiVersion);
  const moduleTransforms: Record<string, FileTransforms> = {
    'expo-updates': {
      content: [
        {
          paths: './src/main/{java,kotlin}/expo/modules/updates/UpdatesPackage.kt',
          transform: (text: string) =>
            deleteLinesBetweenTags(
              /WHEN_VERSIONING_REMOVE_FROM_HERE/,
              /WHEN_VERSIONING_REMOVE_TO_HERE/,
              text
            ),
        },
      ],
    },
  };
  const transforms: FileTransforms = moduleTransforms[module] ?? {};

  return {
    path: [...(base.path ?? []), ...(transforms.path ?? [])],
    content: [...(base.content ?? []), ...(transforms.content ?? [])],
  };
}
