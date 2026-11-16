import { createCn } from 'cn/config'

// index.css에 더한 글자 크기 이름(D48)을 글자 크기로 알린다. 등록하지 않으면 cn()이 text-sub를 색으로 보고
// 뒤에 오는 text-muted-foreground 같은 색 클래스와 겹친다며 지운다.
export const cn = createCn({
  extend: {
    classGroups: {
      'font-size': [{ text: ['body', 'sub', 'caption', 'kbd'] }],
    },
  },
})
