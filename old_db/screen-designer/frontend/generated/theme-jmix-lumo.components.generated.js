import { unsafeCSS, registerStyles } from '@vaadin/vaadin-themable-mixin/register-styles';

import vaadinFormLayoutCss from 'themes/jmix-lumo/components/vaadin-form-layout.css?inline';


if (!document['_vaadintheme_jmix-lumo_componentCss']) {
  registerStyles(
        'vaadin-form-layout',
        unsafeCSS(vaadinFormLayoutCss.toString())
      );
      
  document['_vaadintheme_jmix-lumo_componentCss'] = true;
}

if (import.meta.hot) {
  import.meta.hot.accept((module) => {
    window.location.reload();
  });
}

