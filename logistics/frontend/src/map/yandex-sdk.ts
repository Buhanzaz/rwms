import * as React from 'react';
import * as ReactDOM from 'react-dom';
import type * as YandexAPI from '@yandex/ymaps3-types';

/** The SDK lives in a disposable frame so replacing a key does not reload logistics. */
export async function loadYandexReactify(apiKey: string) {
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    const url = new URL('https://api-maps.yandex.ru/v3/');
    url.searchParams.set('apikey', apiKey);
    url.searchParams.set('lang', 'ru_RU');
    script.src = url.href;
    script.async = true;
    script.onload = () => resolve();
    // SDK errors may contain URLs with credentials. Only expose a fixed message.
    script.onerror = () => reject(new Error('Яндекс Карты недоступны.'));
    document.head.append(script);
  });
  const api = (window as Window & { ymaps3?: typeof YandexAPI }).ymaps3;
  if (!api) throw new Error('Яндекс Карты недоступны.');
  await api.ready;
  const [reactifyModule, projectionModule] = await Promise.all([
    api.import('@yandex/ymaps3-reactify'),
    api.import('@yandex/ymaps3-spherical-mercator-projection@0.0.1'),
  ]);
  const reactify = reactifyModule.reactify.bindTo(React, ReactDOM);
  const components = reactify.module(api);
  return {
    components: {
      // Upstream's generic mapper misclassifies the root entity as a React context.
      // Use the SDK's public YMapProps at this Reactify boundary.
      YMap: components.YMap as unknown as React.ComponentType<React.PropsWithChildren<YandexAPI.YMapProps>>,
      YMapDefaultSchemeLayer: components.YMapDefaultSchemeLayer,
    },
    projection: new projectionModule.SphericalMercator(),
  };
}
