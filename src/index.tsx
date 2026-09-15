import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'animal-island-ui/style';
import './styles.css';
import App from './App';
import { installNativeStartupCheck } from './utils/nativePlatform';

// 安卓壳里先做一次相册插件的只读自检：真机上排查「保存没反应」时，
// 这里是第一现场（结果同时挂在 window.__tietiedaoNative 上）。
void installNativeStartupCheck();

const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('Root element not found');
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
